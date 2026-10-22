package simpledb.plan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import simpledb.materialize.DistinctPlan;
import simpledb.materialize.SortPlan;
import simpledb.query.Scan;
import simpledb.record.Schema;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Deterministic checks for non-executing physical plan display. */
public class PlanExplainerTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      testExactTreeWithoutOpeningScans();
      testOperatorDescriptions();
      System.out.println("All " + checksRun
            + " plan explainer checks passed.");
   }

   private static void testExactTreeWithoutOpeningScans() {
      TestPlan left = new TestPlan("Leaf(name=left)", 2, 3, "leftid");
      TestPlan right = new TestPlan("Leaf(name=right)", 5, 4, "rightid");
      Plan product = new ProductPlan(left, right);
      String expected = "Product [blocks=17, records=12]" + System.lineSeparator()
            + "  Leaf(name=left) [blocks=2, records=3]"
            + System.lineSeparator()
            + "  Leaf(name=right) [blocks=5, records=4]";

      String explanation = PlanExplainer.explain(product);
      check(explanation.equals(expected),
            "Unexpected plan tree:" + System.lineSeparator() + explanation);
      check(explanation.equals(PlanExplainer.explain(product)),
            "Plan display should be stable across repeated calls");
      check(!explanation.contains("@"),
            "Plan display must not include object identity text");
   }

   private static void testOperatorDescriptions() throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-plan-explainer-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Transaction tx = db.newTx();
      TestPlan leaf = new TestPlan("Leaf(name=input)", 1, 2, "id");
      SortPlan sort = new SortPlan(tx, leaf, Arrays.asList("id"),
            Arrays.asList(false));
      DistinctPlan distinct = new DistinctPlan(tx, leaf);

      check(sort.operationDescription().equals(
            "Sort(fields=[id], directions=[desc])"),
            "Sort description should include fields and directions");
      check(distinct.operationDescription().equals("Distinct(fields=[id])"),
            "Distinct description should include all tuple fields");
      check(PlanExplainer.explain(distinct).contains("  Sort(fields=[id],"
            + " directions=[asc])"),
            "Distinct display should expose its physical sort child");
      tx.commit();
   }

   private static class TestPlan implements Plan {
      private String description;
      private int blocks;
      private int records;
      private Schema schema = new Schema();

      TestPlan(String description, int blocks, int records, String field) {
         this.description = description;
         this.blocks = blocks;
         this.records = records;
         schema.addIntField(field);
      }

      public Scan open() {
         throw new AssertionError("PlanExplainer must not open a scan");
      }

      public int blocksAccessed() {
         return blocks;
      }

      public int recordsOutput() {
         return records;
      }

      public int distinctValues(String fldname) {
         return records;
      }

      public Schema schema() {
         return schema;
      }

      public String operationDescription() {
         return description;
      }

      public List<Plan> children() {
         return Collections.emptyList();
      }
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
