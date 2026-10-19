package simpledb.materialize;

import static java.sql.Types.INTEGER;
import static java.sql.Types.VARCHAR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import simpledb.plan.Plan;
import simpledb.plan.ProjectPlan;
import simpledb.plan.TablePlan;
import simpledb.plan.Planner;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Integration checks for the standalone sort-based DISTINCT operator. */
public class DistinctPlanTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-distinct-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      createRows(planner, tx);
      testMultiFieldDuplicates(db, tx);
      testSingleFieldDuplicates(db, tx);
      testEmptyInput(db, tx);

      tx.commit();
      System.out.println("All " + checksRun
            + " DISTINCT plan checks passed.");
   }

   private static void createRows(Planner planner, Transaction tx) {
      planner.executeUpdate(
            "create table items(id int, name varchar(10), score int)", tx);
      insert(planner, tx, 1, "amy", 7);
      insert(planner, tx, 2, "bob", 7);
      insert(planner, tx, 3, "amy", 4);
      insert(planner, tx, 4, "amy", 7);
      insert(planner, tx, 5, "bob", 7);
      planner.executeUpdate("create table empty(id int, name varchar(10))", tx);
   }

   private static void insert(Planner planner, Transaction tx, int id,
                              String name, int score) {
      int count = planner.executeUpdate(
            "insert into items(id, name, score) values (" + id + ", '"
            + name + "', " + score + ")", tx);
      check(count == 1, "Insert count should be one");
   }

   private static void testMultiFieldDuplicates(SimpleDB db, Transaction tx) {
      Plan projected = new ProjectPlan(table(db, tx, "items"),
            Arrays.asList("score", "name"));
      Plan distinct = new DistinctPlan(tx, projected);

      check(distinct.schema().type("score") == INTEGER,
            "DISTINCT should retain integer field types");
      check(distinct.schema().type("name") == VARCHAR,
            "DISTINCT should retain string field types");
      check(distinct.schema().length("name") == 10,
            "DISTINCT should retain string lengths");

      Scan scan = distinct.open();
      List<String> expected = Arrays.asList("4:amy", "7:amy", "7:bob");
      check(rows(scan).equals(expected),
            "Unexpected multi-field DISTINCT rows");
      scan.beforeFirst();
      check(rows(scan).equals(expected),
            "DISTINCT scan should rewind correctly");
      scan.close();
   }

   private static void testSingleFieldDuplicates(SimpleDB db, Transaction tx) {
      Plan projected = new ProjectPlan(table(db, tx, "items"),
            Arrays.asList("name"));
      Scan scan = new DistinctPlan(tx, projected).open();
      List<String> actual = new ArrayList<String>();
      while (scan.next())
         actual.add(scan.getString("name"));
      scan.close();

      check(actual.equals(Arrays.asList("amy", "bob")),
            "DISTINCT should remove duplicate string values");
   }

   private static void testEmptyInput(SimpleDB db, Transaction tx) {
      Plan distinct = new DistinctPlan(tx, table(db, tx, "empty"));
      Scan scan = distinct.open();
      check(!scan.next(), "DISTINCT over empty input should be empty");
      scan.close();
   }

   private static List<String> rows(Scan scan) {
      List<String> rows = new ArrayList<String>();
      while (scan.next())
         rows.add(scan.getInt("score") + ":" + scan.getString("name"));
      return rows;
   }

   private static Plan table(SimpleDB db, Transaction tx, String name) {
      return new TablePlan(tx, name, db.mdMgr());
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
