package simpledb.opt;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import simpledb.index.planner.IndexJoinPlan;
import simpledb.materialize.MergeJoinPlan;
import simpledb.metadata.IndexInfo;
import simpledb.multibuffer.MultibufferProductPlan;
import simpledb.parse.Parser;
import simpledb.parse.QueryData;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.plan.SelectPlan;
import simpledb.plan.TablePlan;
import simpledb.query.Expression;
import simpledb.query.NestedLoopJoinScan;
import simpledb.query.Predicate;
import simpledb.query.Scan;
import simpledb.query.Term;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/**
 * Integration tests for index, sort-merge, and nested-loops joins.
 *
 * <p>This class creates its database in the operating system's temporary
 * directory and therefore never creates or modifies {@code studentdb}.</p>
 */
public class JoinIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-joins-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      try {
         createTables(planner, tx);
         testNestedLoopJoin(db, tx);
         testMergeJoin(db, tx);
         testIndexJoin(db, planner, tx);
         testTablePlannerChoices(db, tx);
         testPlannerIntegration(planner, tx);
         tx.commit();
      }
      catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }

      System.out.println("All " + checksRun + " join integration checks passed.");
   }

   private static void createTables(Planner planner, Transaction tx) {
      execute(planner, tx,
            "create table leftint(lid int, ikey int, lgroup int)");
      execute(planner, tx,
            "create table rightint(rid int, jkey int, rgroup int)");
      execute(planner, tx,
            "insert into leftint(lid, ikey, lgroup) values (1, 1, 1)");
      execute(planner, tx,
            "insert into leftint(lid, ikey, lgroup) values (2, 2, 1)");
      execute(planner, tx,
            "insert into leftint(lid, ikey, lgroup) values (3, 2, 2)");
      execute(planner, tx,
            "insert into leftint(lid, ikey, lgroup) values (4, 4, 2)");
      execute(planner, tx,
            "insert into rightint(rid, jkey, rgroup) values (10, 2, 1)");
      execute(planner, tx,
            "insert into rightint(rid, jkey, rgroup) values (11, 2, 2)");
      execute(planner, tx,
            "insert into rightint(rid, jkey, rgroup) values (12, 3, 2)");
      execute(planner, tx,
            "insert into rightint(rid, jkey, rgroup) values (13, 5, 3)");

      execute(planner, tx,
            "create table leftstr(lid int, skey varchar(10))");
      execute(planner, tx,
            "create table rightstr(rid int, tkey varchar(10))");
      execute(planner, tx,
            "insert into leftstr(lid, skey) values (1, 'blue')");
      execute(planner, tx,
            "insert into leftstr(lid, skey) values (2, 'blue')");
      execute(planner, tx,
            "insert into leftstr(lid, skey) values (3, 'green')");
      execute(planner, tx,
            "insert into rightstr(rid, tkey) values (10, 'blue')");
      execute(planner, tx,
            "insert into rightstr(rid, tkey) values (11, 'blue')");
      execute(planner, tx,
            "insert into rightstr(rid, tkey) values (12, 'red')");

      execute(planner, tx,
            "create table nomatchright(rid int, nokey int)");
      execute(planner, tx,
            "insert into nomatchright(rid, nokey) values (1, 7)");
      execute(planner, tx,
            "insert into nomatchright(rid, nokey) values (2, 8)");

      execute(planner, tx,
            "create table emptyleft(lid int, ekey int)");
      execute(planner, tx,
            "create table emptyright(rid int, fkey int)");
      execute(planner, tx,
            "insert into emptyleft(lid, ekey) values (1, 1)");
      execute(planner, tx,
            "insert into emptyright(rid, fkey) values (1, 1)");

      execute(planner, tx,
            "create table emptymergesleft(skey varchar(10))");
      execute(planner, tx,
            "create table emptymergesright(tkey varchar(10))");

      execute(planner, tx,
            "create table outeridx(oid int, okey int)");
      execute(planner, tx,
            "create table indexed(iid int, ikey int)");
      execute(planner, tx,
            "create index idx_indexed_ikey on indexed(ikey) using hash");
      execute(planner, tx,
            "insert into outeridx(oid, okey) values (1, 2)");
      execute(planner, tx,
            "insert into outeridx(oid, okey) values (2, 2)");
      execute(planner, tx,
            "insert into outeridx(oid, okey) values (3, 7)");
      execute(planner, tx,
            "insert into indexed(iid, ikey) values (10, 2)");
      execute(planner, tx,
            "insert into indexed(iid, ikey) values (11, 2)");
      execute(planner, tx,
            "insert into indexed(iid, ikey) values (12, 3)");
      execute(planner, tx,
            "create table emptyindexouter(oid int, okey int)");

      execute(planner, tx,
            "create table labels(labelid int)");
      execute(planner, tx,
            "insert into labels(labelid) values (1)");
      execute(planner, tx,
            "insert into labels(labelid) values (2)");

      execute(planner, tx,
            "create table third(tid int, tkey int)");
      execute(planner, tx,
            "insert into third(tid, tkey) values (100, 2)");
      execute(planner, tx,
            "insert into third(tid, tkey) values (101, 2)");
   }

   private static void testNestedLoopJoin(SimpleDB db, Transaction tx) {
      Plan left = tablePlan(db, tx, "leftint");
      Plan right = tablePlan(db, tx, "rightint");

      Plan equality = new NestedLoopJoinPlan(left, right, fieldComparison("ikey", "=", "jkey"));
      Scan nestedScan = equality.open();
      check(nestedScan instanceof NestedLoopJoinScan,
            "NestedLoopJoinPlan must open a NestedLoopJoinScan");
      nestedScan.close();
      assertPairs(equality, "lid", "rid", "2:10", "2:11", "3:10", "3:11");
      assertSecondScanProducesSameRows(equality, "lid", "rid", 4);

      assertCount(new NestedLoopJoinPlan(left, right, fieldComparison("ikey", "<", "jkey")), 9,
            "Nested loops must support <");
      assertCount(new NestedLoopJoinPlan(left, right, fieldComparison("ikey", "<=", "jkey")), 13,
            "Nested loops must support <=");
      assertCount(new NestedLoopJoinPlan(left, right, fieldComparison("ikey", ">", "jkey")), 3,
            "Nested loops must support >");
      assertCount(new NestedLoopJoinPlan(left, right, fieldComparison("ikey", ">=", "jkey")), 7,
            "Nested loops must support >=");
      assertCount(new NestedLoopJoinPlan(left, right, fieldComparison("ikey", "!=", "jkey")), 12,
            "Nested loops must support !=");
      assertCount(new NestedLoopJoinPlan(left, right, fieldComparison("ikey", "<>", "jkey")), 12,
            "Nested loops must support <>");

      Plan conjunction = new NestedLoopJoinPlan(left, right,
            conjunction(fieldComparison("ikey", "=", "jkey"),
                        fieldComparison("lgroup", "=", "rgroup")));
      assertPairs(conjunction, "lid", "rid", "2:10", "3:11");

      Plan noMatches = new NestedLoopJoinPlan(left, tablePlan(db, tx, "nomatchright"),
            fieldComparison("ikey", "=", "nokey"));
      assertCount(noMatches, 0, "Nested loops must return no rows when no keys match");

      Plan emptyLeft = new NestedLoopJoinPlan(tablePlan(db, tx, "emptymergesleft"),
            tablePlan(db, tx, "rightstr"), fieldComparison("skey", "=", "tkey"));
      assertCount(emptyLeft, 0, "Nested loops must handle an empty left input");

      Plan emptyRight = new NestedLoopJoinPlan(tablePlan(db, tx, "leftstr"),
            tablePlan(db, tx, "emptymergesright"), fieldComparison("skey", "=", "tkey"));
      assertCount(emptyRight, 0, "Nested loops must handle an empty right input");
   }

   private static void testMergeJoin(SimpleDB db, Transaction tx) {
      Plan integerMerge = new MergeJoinPlan(tx, tablePlan(db, tx, "leftint"),
            tablePlan(db, tx, "rightint"), "ikey", "jkey");
      Scan mergeScan = integerMerge.open();
      check(mergeScan.getClass().getName().equals("simpledb.materialize.MergeJoinScan"),
            "MergeJoinPlan must open a MergeJoinScan");
      mergeScan.close();
      assertPairs(integerMerge, "lid", "rid", "2:10", "2:11", "3:10", "3:11");

      Plan stringMerge = new MergeJoinPlan(tx, tablePlan(db, tx, "leftstr"),
            tablePlan(db, tx, "rightstr"), "skey", "tkey");
      assertPairs(stringMerge, "lid", "rid", "1:10", "1:11", "2:10", "2:11");

      Plan noMatches = new MergeJoinPlan(tx, tablePlan(db, tx, "leftint"),
            tablePlan(db, tx, "nomatchright"), "ikey", "nokey");
      assertCount(noMatches, 0, "Merge join must return no rows when no keys match");

      Plan emptyLeft = new MergeJoinPlan(tx, tablePlan(db, tx, "emptymergesleft"),
            tablePlan(db, tx, "rightstr"), "skey", "tkey");
      assertCount(emptyLeft, 0, "Merge join must handle an empty left input");

      Plan emptyRight = new MergeJoinPlan(tx, tablePlan(db, tx, "leftstr"),
            tablePlan(db, tx, "emptymergesright"), "skey", "tkey");
      assertCount(emptyRight, 0, "Merge join must handle an empty right input");
   }

   private static void testIndexJoin(SimpleDB db, Planner planner, Transaction tx) {
      Plan indexJoin = newIndexJoin(db, tx, "outeridx");
      assertPairs(indexJoin, "oid", "iid", "1:10", "1:11", "2:10", "2:11");

      Plan noMatches = newIndexJoin(db, tx, "emptyindexouter");
      assertCount(noMatches, 0, "Index join must handle an empty left input");

      execute(planner, tx, "update indexed set ikey = 7 where iid = 11");
      assertPairs(newIndexJoin(db, tx, "outeridx"), "oid", "iid",
            "1:10", "2:10", "3:11");

      execute(planner, tx, "insert into indexed(iid, ikey) values (13, 2)");
      assertPairs(newIndexJoin(db, tx, "outeridx"), "oid", "iid",
            "1:10", "1:13", "2:10", "2:13", "3:11");
   }

   private static void testTablePlannerChoices(SimpleDB db, Transaction tx) {
      Plan indexForward = planTwoTables(db, tx,
            "select oid, iid from outeridx, indexed where okey = ikey");
      assertCorePlan(indexForward, IndexJoinPlan.class,
            "An indexed equality join must select IndexJoinPlan");
      assertCount(indexForward, 5, "Index equality query should return expected rows");

      Plan indexReverse = planTwoTables(db, tx,
            "select oid, iid from outeridx, indexed where ikey = okey");
      assertCorePlan(indexReverse, IndexJoinPlan.class,
            "Reversed equality operands must still select IndexJoinPlan");
      assertCount(indexReverse, 5, "Reversed index equality should return expected rows");

      Plan merge = planTwoTables(db, tx,
            "select lid, rid from leftint, rightint where ikey = jkey");
      assertCorePlan(merge, MergeJoinPlan.class,
            "An unindexed equality join must select MergeJoinPlan");
      assertPairs(merge, "lid", "rid", "2:10", "2:11", "3:10", "3:11");

      Plan residual = planTwoTables(db, tx,
            "select lid, rid from leftint, rightint "
            + "where ikey = jkey and lgroup = rgroup");
      assertCorePlan(residual, MergeJoinPlan.class,
            "Merge join should retain a residual join predicate");
      assertPairs(residual, "lid", "rid", "2:10", "3:11");

      Plan nested = planTwoTables(db, tx,
            "select lid, rid from leftint, rightint where ikey < jkey");
      assertCorePlan(nested, NestedLoopJoinPlan.class,
            "A non-equality join must select NestedLoopJoinPlan");
      assertCount(nested, 9, "Nested-loop query should return expected rows");

      Plan product = productTwoTables(db, tx,
            "select lid, labelid from leftint, labels");
      check(product instanceof MultibufferProductPlan,
            "Unrelated tables must use the cross-product path");
      assertCount(product, 8, "Cross product should contain every pair of rows");
   }

   private static void testPlannerIntegration(Planner planner, Transaction tx) {
      assertCount(planner.createQueryPlan(
            "select lid, rid from leftint, rightint where ikey = jkey", tx), 4,
            "Heuristic planner must execute an unindexed equality join");

      assertCount(planner.createQueryPlan(
            "select lid, rid from leftint, rightint "
            + "where ikey = jkey and rid > 10", tx), 2,
            "Table-local selections must be applied before or during the join");

      assertCount(planner.createQueryPlan(
            "select tid, rid, lid from third, rightint, leftint "
            + "where jkey = tkey and ikey = jkey", tx), 8,
            "A three-table query must build a valid left-deep plan");

      assertCount(planner.createQueryPlan(
            "select oid, iid from outeridx, indexed where okey = ikey", tx), 5,
            "First query through a reused heuristic planner must work");
      assertCount(planner.createQueryPlan(
            "select lid, labelid from leftint, labels", tx), 8,
            "A subsequent query through the same heuristic planner must work");
   }

   private static Plan newIndexJoin(SimpleDB db, Transaction tx, String outerTable) {
      IndexInfo index = db.mdMgr().getIndexInfo("indexed", tx).get("ikey");
      check(index != null, "The hash index metadata must be available");
      return new IndexJoinPlan(tablePlan(db, tx, outerTable),
            tablePlan(db, tx, "indexed"), index, "okey");
   }

   private static Plan planTwoTables(SimpleDB db, Transaction tx, String sql) {
      QueryData data = new Parser(sql).query();
      List<String> tables = new ArrayList<String>(data.tables());
      check(tables.size() == 2, "Test query must name exactly two tables");

      TablePlanner outer = new TablePlanner(tables.get(0), data.pred(), tx, db.mdMgr());
      TablePlanner inner = new TablePlanner(tables.get(1), data.pred(), tx, db.mdMgr());
      Plan joined = inner.makeJoinPlan(outer.makeSelectPlan());
      check(joined != null, "The test query must produce a join plan");
      return joined;
   }

   private static Plan productTwoTables(SimpleDB db, Transaction tx, String sql) {
      QueryData data = new Parser(sql).query();
      List<String> tables = new ArrayList<String>(data.tables());
      TablePlanner outer = new TablePlanner(tables.get(0), data.pred(), tx, db.mdMgr());
      TablePlanner inner = new TablePlanner(tables.get(1), data.pred(), tx, db.mdMgr());
      Plan current = outer.makeSelectPlan();
      check(inner.makeJoinPlan(current) == null,
            "Tables without a connecting predicate must not create a join plan");
      return inner.makeProductPlan(current);
   }

   private static Plan tablePlan(SimpleDB db, Transaction tx, String tableName) {
      return new TablePlan(tx, tableName, db.mdMgr());
   }

   private static Predicate fieldComparison(String lhs, String operator, String rhs) {
      return new Predicate(new Term(new Expression(lhs), operator, new Expression(rhs)));
   }

   private static Predicate conjunction(Predicate first, Predicate second) {
      first.conjoinWith(second);
      return first;
   }

   private static void assertSecondScanProducesSameRows(Plan plan, String leftField,
                                                         String rightField, int expectedCount) {
      Scan scan = plan.open();
      int firstCount = countRows(scan);
      scan.beforeFirst();
      int secondCount = countRows(scan);
      scan.close();
      check(firstCount == expectedCount && secondCount == expectedCount,
            "beforeFirst() must allow a complete second nested-loop scan");
   }

   private static void assertPairs(Plan plan, String leftField, String rightField,
                                   String... expectedPairs) {
      Scan scan = plan.open();
      List<String> actual = new ArrayList<String>();
      while (scan.next())
         actual.add(scan.getInt(leftField) + ":" + scan.getInt(rightField));
      scan.close();

      List<String> expected = new ArrayList<String>(Arrays.asList(expectedPairs));
      Collections.sort(actual);
      Collections.sort(expected);
      check(actual.equals(expected),
            "Expected pairs " + expected + ", but got " + actual);
   }

   private static void assertCount(Plan plan, int expectedCount, String message) {
      Scan scan = plan.open();
      int actualCount = countRows(scan);
      scan.close();
      check(actualCount == expectedCount,
            message + "; expected " + expectedCount + " rows, but got " + actualCount);
   }

   private static int countRows(Scan scan) {
      int count = 0;
      while (scan.next())
         count++;
      return count;
   }

   /**
    * TablePlanner adds SelectPlan wrappers for index and merge joins. Unwrap
    * those known wrappers so this test can verify the physical join operator
    * before a ProjectPlan is introduced by HeuristicQueryPlanner.
    */
   private static void assertCorePlan(Plan plan, Class<?> expectedClass, String message) {
      Plan core = plan;
      while (core instanceof SelectPlan)
         core = selectChild((SelectPlan) core);
      check(expectedClass.isInstance(core),
            message + "; expected " + expectedClass.getSimpleName()
            + ", but got " + core.getClass().getSimpleName());
   }

   private static Plan selectChild(SelectPlan plan) {
      try {
         Field field = SelectPlan.class.getDeclaredField("p");
         field.setAccessible(true);
         return (Plan) field.get(plan);
      }
      catch (ReflectiveOperationException e) {
         throw new AssertionError("Unable to inspect SelectPlan's child plan", e);
      }
   }

   private static void execute(Planner planner, Transaction tx, String sql) {
      planner.executeUpdate(sql, tx);
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
