package simpledb.materialize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import simpledb.parse.Parser;
import simpledb.plan.BasicQueryPlanner;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Integration checks for SELECT DISTINCT planner placement and execution. */
public class DistinctQueryIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-distinct-query-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      createRows(planner, tx);
      testHeuristicPlanner(planner, tx);
      testBasicPlanner(db, tx);

      tx.commit();
      System.out.println("All " + checksRun
            + " DISTINCT query integration checks passed.");
   }

   private static void createRows(Planner planner, Transaction tx) {
      planner.executeUpdate(
            "create table items(id int, name varchar(10), score int)", tx);
      insert(planner, tx, 1, "amy", 7);
      insert(planner, tx, 2, "bob", 7);
      insert(planner, tx, 3, "amy", 4);
      insert(planner, tx, 4, "amy", 7);
      insert(planner, tx, 5, "bob", 7);
   }

   private static void insert(Planner planner, Transaction tx, int id,
                              String name, int score) {
      int count = planner.executeUpdate(
            "insert into items(id, name, score) values (" + id + ", '"
            + name + "', " + score + ")", tx);
      check(count == 1, "Insert count should be one");
   }

   private static void testHeuristicPlanner(Planner planner, Transaction tx) {
      Plan distinct = planner.createQueryPlan(
            "select distinct name from items", tx);
      check(distinct instanceof DistinctPlan,
            "Heuristic planner should add DistinctPlan after projection");
      check(strings(distinct, "name").equals(Arrays.asList("amy", "bob")),
            "SELECT DISTINCT should remove duplicate projected rows");

      Plan ordered = planner.createQueryPlan(
            "select distinct name, score from items where score >= 7 "
            + "order by name desc, score asc", tx);
      check(ordered instanceof SortPlan,
            "ORDER BY should remain the outermost plan after DISTINCT");
      check(pairs(ordered).equals(Arrays.asList("bob:7", "amy:7")),
            "DISTINCT, WHERE, and ORDER BY should compose correctly");

      Plan aggregate = planner.createQueryPlan(
            "select distinct count(*) from items", tx);
      check(ints(aggregate, "countofall").equals(Arrays.asList(5)),
            "DISTINCT should be applied after aggregate projection");
   }

   private static void testBasicPlanner(SimpleDB db, Transaction tx) {
      BasicQueryPlanner basic = new BasicQueryPlanner(db.mdMgr());
      Plan distinct = basic.createPlan(new Parser(
            "select distinct score from items").query(), tx);
      check(distinct instanceof DistinctPlan,
            "Basic planner should add DistinctPlan after projection");
      check(ints(distinct, "score").equals(Arrays.asList(4, 7)),
            "Basic planner should execute SELECT DISTINCT");
   }

   private static List<String> strings(Plan plan, String field) {
      Scan scan = plan.open();
      List<String> values = new ArrayList<String>();
      while (scan.next())
         values.add(scan.getString(field));
      scan.close();
      return values;
   }

   private static List<Integer> ints(Plan plan, String field) {
      Scan scan = plan.open();
      List<Integer> values = new ArrayList<Integer>();
      while (scan.next())
         values.add(scan.getInt(field));
      scan.close();
      return values;
   }

   private static List<String> pairs(Plan plan) {
      Scan scan = plan.open();
      List<String> values = new ArrayList<String>();
      while (scan.next())
         values.add(scan.getString("name") + ":" + scan.getInt("score"));
      scan.close();
      return values;
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
