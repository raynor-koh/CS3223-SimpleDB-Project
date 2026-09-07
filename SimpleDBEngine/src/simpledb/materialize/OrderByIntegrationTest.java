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

/**
 * Integration tests for integer, string, descending, and mixed ORDER BY.
 */
public class OrderByIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-order-by-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      createRows(planner, tx);
      testHeuristicPlanner(planner, tx);
      testBasicPlanner(db, tx);

      tx.commit();
      System.out.println(
            "All " + checksRun + " ORDER BY integration checks passed.");
   }

   private static void createRows(Planner planner, Transaction tx) {
      planner.executeUpdate(
            "create table items(id int, name varchar(10), score int)", tx);
      planner.executeUpdate(
            "create index idx_items_score on items(score) using btree", tx);
      insert(planner, tx, 1, "bob", 20);
      insert(planner, tx, 2, "amy", 10);
      insert(planner, tx, 3, "cara", 20);
      insert(planner, tx, 4, "zoe", 10);
      insert(planner, tx, 5, "erin", 20);
   }

   private static void insert(Planner planner, Transaction tx,
                              int id, String name, int score) {
      int count = planner.executeUpdate(
            "insert into items(id, name, score) values ("
            + id + ", '" + name + "', " + score + ")", tx);
      check(count == 1, "Insert count should be one");
   }

   private static void testHeuristicPlanner(Planner planner,
                                            Transaction tx) {
      Plan unsorted = planner.createQueryPlan(
            "select id, name, score from items", tx);
      check(!(unsorted instanceof SortPlan),
            "A query without ORDER BY must not add a SortPlan");

      Plan sorted = planner.createQueryPlan(
            "select id, name, score from items "
            + "order by score asc, name desc", tx);
      check(sorted instanceof SortPlan,
            "A query with ORDER BY should add a SortPlan");
      assertIds(sorted, 4, 2, 5, 3, 1);

      assertIds(planner.createQueryPlan(
            "select id, name, score from items "
            + "order by score desc, name", tx),
            1, 3, 5, 2, 4);

      assertIds(planner.createQueryPlan(
            "select id, name, score from items order by name desc", tx),
            4, 5, 3, 1, 2);

      assertIds(planner.createQueryPlan(
            "select id, name, score from items where score = 20 "
            + "order by name desc", tx),
            5, 3, 1);

      Plan empty = planner.createQueryPlan(
            "select id, score from items where score > 100 "
            + "order by score", tx);
      Scan scan = empty.open();
      check(!scan.next(), "Sorting an empty result should remain empty");
      scan.close();
   }

   private static void testBasicPlanner(SimpleDB db, Transaction tx) {
      BasicQueryPlanner basic = new BasicQueryPlanner(db.mdMgr());
      Plan plan = basic.createPlan(new Parser(
            "select id, name, score from items "
            + "order by score desc, name asc").query(), tx);
      check(plan instanceof SortPlan,
            "BasicQueryPlanner should add a SortPlan for ORDER BY");
      assertIds(plan, 1, 3, 5, 2, 4);
   }

   private static void assertIds(Plan plan, Integer... expectedIds) {
      Scan scan = plan.open();
      List<Integer> actualIds = new ArrayList<Integer>();
      while (scan.next())
         actualIds.add(scan.getInt("id"));
      scan.close();

      List<Integer> expected = Arrays.asList(expectedIds);
      check(actualIds.equals(expected),
            "Expected row order " + expected + ", but got " + actualIds);
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
