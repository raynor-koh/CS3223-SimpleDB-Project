package simpledb.materialize;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.plan.BasicUpdatePlanner;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.plan.Plan;
import simpledb.plan.PlanExplainer;
import simpledb.plan.Planner;
import simpledb.plan.TablePlan;
import simpledb.query.Constant;
import simpledb.query.Expression;
import simpledb.query.Predicate;
import simpledb.query.Scan;
import simpledb.query.Term;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Rewind the same merge scan, preserving every duplicate pair and sort bookmark. */
public class MergeJoinRewindIntegrationTest {
   private static int checks;

   public static void main(String[] args) throws Exception {
      exercise(new int[] {7, 7}, new int[] {7, 7});
      exercise(new int[] {1, 1, 2, 2}, new int[] {1, 2, 1, 2});
      exercise(new int[] {1, 1, 2, 2}, new int[] {2, 1, 2, 1, 2, 1});
      exercise(new int[] {1, 1, 2}, new int[] {1, 1});
      exercise(new int[] {1, 1}, new int[] {2, 2});
      exercise(new int[0], new int[] {1, 1});
      exercise(new int[] {1, 1}, new int[0]);
      exercise(new int[0], new int[0]);
      testSortBookmark();
      System.out.println("All " + checks + " merge-rewind integration checks passed.");
   }

   private static void exercise(int[] left, int[] right) throws Exception {
      SimpleDB db = new SimpleDB(Files.createTempDirectory("simpledb-merge-rewind-")
            .resolve("db").toString());
      Transaction tx = db.newTx();
      try {
         Planner setup = db.planner();
         setup.executeUpdate("create table ml(lid int, lk int)", tx);
         setup.executeUpdate("create table mr(rid int, rk int)", tx);
         for (int i = 0; i < left.length; i++)
            setup.executeUpdate("insert into ml(lid, lk) values (" + i + ", " + left[i] + ")", tx);
         for (int i = 0; i < right.length; i++)
            setup.executeUpdate("insert into mr(rid, rk) values (" + i + ", " + right[i] + ")", tx);
         setup.executeUpdate("create table driver(did int)", tx);
         setup.executeUpdate("insert into driver(did) values (1)", tx);
         setup.executeUpdate("insert into driver(did) values (2)", tx);

         Map<String, Integer> expected = new LinkedHashMap<>();
         for (int i = 0; i < left.length; i++)
            for (int j = 0; j < right.length; j++)
               if (left[i] == right[j]) expected.put(i + ":" + j, 1);
         Planner planner = new Planner(new HeuristicQueryPlanner(db.mdMgr(), JoinPolicy.only(JoinMethod.MERGE)),
               new BasicUpdatePlanner(db.mdMgr()));
         Plan plan = planner.createQueryPlan("select lid, rid from ml, mr where lk = rk", tx);
         check(PlanExplainer.explain(plan).contains("MergeJoin"), "Expected merge-only physical plan");
         exerciseRewinds(tx, plan, expected);

         // Nested loops rewinds its RHS for each driver row without reopening it.
         Plan composed = new NestedLoopJoinPlan(new TablePlan(tx, "driver", db.mdMgr()), plan,
               new Predicate(new Term(new Expression("did"), ">", new Expression(new Constant(0)))));
         Map<String, Integer> doubled = new LinkedHashMap<>();
         expected.forEach((pair, count) -> doubled.put(pair, count * 2));
         exerciseRewinds(tx, composed, doubled);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void exerciseRewinds(Transaction tx, Plan plan, Map<String, Integer> expected) {
      int buffers = tx.availableBuffs();
      Scan scan = plan.open();
      try {
         scan.beforeFirst();
         check(read(scan).equals(expected), "Initial bag mismatch: " + expected);
         for (int pass = 0; pass < 3; pass++) {
            scan.beforeFirst();
            check(read(scan).equals(expected), "Full rewind lost duplicate pairs");
            scan.beforeFirst();
            check(scan.next() == !expected.isEmpty(), "Partial scan availability differs");
            scan.beforeFirst();
            check(read(scan).equals(expected), "Partial rewind lost duplicate pairs");
         }
      } finally {
         scan.close();
      }
      check(tx.availableBuffs() == buffers, "Rewound merge scan leaked buffers");
   }

   private static Map<String, Integer> read(Scan scan) {
      Map<String, Integer> result = new LinkedHashMap<>();
      while (scan.next())
         result.merge(scan.getInt("lid") + ":" + scan.getInt("rid"), 1, Integer::sum);
      return result;
   }

   private static void testSortBookmark() throws Exception {
      SimpleDB db = new SimpleDB(Files.createTempDirectory("simpledb-sort-bookmark-")
            .resolve("db").toString());
      Transaction tx = db.newTx();
      try {
         db.planner().executeUpdate("create table sorted(id int)", tx);
         // Exactly two natural runs: [1,3] and [1,2]. Equal heads select run 2.
         for (int id : new int[] {1, 3, 1, 2})
            db.planner().executeUpdate("insert into sorted(id) values (" + id + ")", tx);
         int buffers = tx.availableBuffs();
         SortScan scan = (SortScan) new SortPlan(tx, new TablePlan(tx, "sorted", db.mdMgr()),
               Arrays.asList("id")).open();
         try {
            check(scan.next(), "Missing first sorted row");
            scan.savePosition();
            for (int pass = 0; pass < 3; pass++) {
               List<Integer> tail = new ArrayList<>();
               while (scan.next()) tail.add(scan.getInt("id"));
               check(tail.equals(Arrays.asList(1, 2, 3)), "Unexpected sorted tail: " + tail);
               scan.restorePosition();
               check(scan.getInt("id") == 1, "Restore selected the wrong run");
            }
         } finally {
            scan.close();
         }
         check(tx.availableBuffs() == buffers, "Sort bookmark test leaked buffers");
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void check(boolean condition, String message) {
      checks++;
      if (!condition) throw new AssertionError(message);
   }
}
