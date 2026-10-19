package simpledb.plan;

import java.nio.file.Files;
import java.nio.file.Path;

import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Integration checks for Planner.explain without query execution. */
public class ExplainIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-explain-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Transaction tx = db.newTx();
      createRows(db.planner(), tx);

      String distinct = db.planner().explain(
            "explain select distinct score from items", tx);
      check(distinct.startsWith("Distinct(fields=[score]) [blocks="),
            "EXPLAIN should display the DISTINCT root plan: " + distinct);
      check(distinct.contains("  Sort(fields=[score], directions=[asc])"),
            "EXPLAIN should display DISTINCT's physical sort");
      check(distinct.contains("    Project(fields=[score])"),
            "EXPLAIN should display projection below DISTINCT");

      String ordered = db.planner().explain(
            "EXPLAIN SELECT DISTINCT score FROM items ORDER BY score DESC", tx);
      check(ordered.startsWith("Sort(fields=[score], directions=[desc])"),
            "ORDER BY should remain the EXPLAIN root plan");
      check(ordered.contains("  Distinct(fields=[score])"),
            "ORDER BY plan should retain DISTINCT below its outer sort");

      tx.commit();
      System.out.println("All " + checksRun
            + " EXPLAIN integration checks passed.");
   }

   private static void createRows(Planner planner, Transaction tx) {
      planner.executeUpdate("create table items(id int, score int)", tx);
      planner.executeUpdate("insert into items(id, score) values (1, 10)", tx);
      planner.executeUpdate("insert into items(id, score) values (2, 10)", tx);
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
