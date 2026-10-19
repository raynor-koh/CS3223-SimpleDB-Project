package simpledb.materialize;

import static java.sql.Types.INTEGER;
import static java.sql.Types.VARCHAR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import simpledb.plan.Plan;
import simpledb.plan.BasicQueryPlanner;
import simpledb.plan.TablePlan;
import simpledb.plan.Planner;
import simpledb.parse.Parser;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Integration checks for sort-based grouping and aggregate functions. */
public class AggregateIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-aggregate-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      createRows(planner, tx);
      testGroupedAggregates(db, tx);
      testGlobalAggregates(db, tx);
      testSchemaTypes(db, tx);
      testIntegerInputValidation(db, tx);
      testEmptyInput(db, tx);
      testOverflow(db, planner, tx);
      testSqlPlannerWiring(db, planner, tx);

      tx.commit();
      System.out.println("All " + checksRun
            + " aggregate integration checks passed.");
   }

   private static void createRows(Planner planner, Transaction tx) {
      planner.executeUpdate(
            "create table items(dept varchar(10), credits int, name varchar(10))",
            tx);
      insert(planner, tx, "math", 10, "zara");
      insert(planner, tx, "math", -4, "amy");
      insert(planner, tx, "cs", 5, "zoe");
      insert(planner, tx, "cs", 7, "bob");
   }

   private static void insert(Planner planner, Transaction tx, String dept,
                              int credits, String name) {
      int count = planner.executeUpdate("insert into items(dept, credits, name) "
            + "values ('" + dept + "', " + credits + ", '" + name + "')", tx);
      check(count == 1, "Insert count should be one");
   }

   private static void testGroupedAggregates(SimpleDB db, Transaction tx) {
      Plan plan = new GroupByPlan(tx, table(db, tx, "items"),
            Arrays.asList("dept"), aggregateFns());
      Scan scan = plan.open();

      check(scan.next(), "Expected cs group");
      assertGroup(scan, "cs", 12, 6, 2, "bob", "zoe");
      check(scan.next(), "Expected math group");
      assertGroup(scan, "math", 6, 3, 2, "amy", "zara");
      check(!scan.next(), "Expected exactly two groups");

      scan.beforeFirst();
      check(scan.next(), "Rewound scan should return the first group");
      assertGroup(scan, "cs", 12, 6, 2, "bob", "zoe");
      scan.close();
   }

   private static void testGlobalAggregates(SimpleDB db, Transaction tx) {
      Plan plan = new GroupByPlan(tx, table(db, tx, "items"),
            Arrays.<String>asList(), aggregateFns());
      Scan scan = plan.open();
      check(scan.next(), "Nonempty global aggregate should emit one row");
      check(scan.getInt("sumofcredits") == 18, "Unexpected global SUM");
      check(scan.getInt("avgofcredits") == 4,
            "AVG should use truncating integer division");
      check(scan.getInt("countofall") == 4, "Unexpected global COUNT(*)");
      check(scan.getString("minofname").equals("amy"), "Unexpected global MIN");
      check(scan.getString("maxofname").equals("zoe"), "Unexpected global MAX");
      check(!scan.next(), "Global aggregate should emit one row");
      scan.close();
   }

   private static void testSchemaTypes(SimpleDB db, Transaction tx) {
      Plan plan = new GroupByPlan(tx, table(db, tx, "items"),
            Arrays.asList("dept"), aggregateFns());
      check(plan.schema().type("sumofcredits") == INTEGER,
            "SUM should have integer output type");
      check(plan.schema().type("avgofcredits") == INTEGER,
            "AVG should have integer output type");
      check(plan.schema().type("countofall") == INTEGER,
            "COUNT should have integer output type");
      check(plan.schema().type("minofname") == VARCHAR,
            "MIN should retain varchar output type");
      check(plan.schema().length("maxofname") == 10,
            "MAX should retain varchar length");
   }

   private static void testIntegerInputValidation(SimpleDB db, Transaction tx) {
      try {
         new GroupByPlan(tx, table(db, tx, "items"),
               Arrays.<String>asList(), Arrays.<AggregationFn>asList(new SumFn("name")));
         throw new AssertionError("SUM(varchar) should be rejected");
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static void testEmptyInput(SimpleDB db, Transaction tx) {
      Plan filteredInput = new simpledb.plan.SelectPlan(table(db, tx, "items"),
            new simpledb.query.Predicate(new simpledb.query.Term(
                  new simpledb.query.Expression("credits"),
                  new simpledb.query.Expression(new simpledb.query.Constant(100)))));
      Plan filtered = new GroupByPlan(tx, filteredInput,
            Arrays.<String>asList(), aggregateFns());
      // The current no-NULL policy emits no global aggregate row for no input.
      Scan scan = filtered.open();
      check(!scan.next(), "Empty global aggregate should emit no row");
      scan.close();
   }

   private static void testOverflow(SimpleDB db, Planner planner, Transaction tx) {
      planner.executeUpdate("create table overflow(value int)", tx);
      planner.executeUpdate("insert into overflow(value) values (2147483647)", tx);
      planner.executeUpdate("insert into overflow(value) values (1)", tx);
      Plan plan = new GroupByPlan(tx, table(db, tx, "overflow"),
            Arrays.<String>asList(), Arrays.<AggregationFn>asList(new SumFn("value")));
      Scan scan = plan.open();
      check(scan.next(), "Overflow group should have a row");
      try {
         scan.getInt("sumofvalue");
         throw new AssertionError("SUM overflow should be reported");
      }
      catch (ArithmeticException expected) {
         checksRun++;
      }
      finally {
         scan.close();
      }
   }

   private static void testSqlPlannerWiring(SimpleDB db, Planner planner,
                                            Transaction tx) {
      Plan grouped = planner.createQueryPlan(
            "select dept, sum(credits), avg(credits), count(*), min(name), max(name) "
            + "from items group by dept order by dept", tx);
      Scan scan = grouped.open();
      check(scan.next(), "SQL group-by query should return cs");
      assertGroup(scan, "cs", 12, 6, 2, "bob", "zoe");
      check(scan.next(), "SQL group-by query should return math");
      assertGroup(scan, "math", 6, 3, 2, "amy", "zara");
      check(!scan.next(), "SQL group-by query should return two rows");
      scan.close();

      Plan distinctGroups = planner.createQueryPlan(
            "select dept from items group by dept order by dept", tx);
      scan = distinctGroups.open();
      check(scan.next() && scan.getString("dept").equals("cs"),
            "GROUP BY without aggregates should return cs");
      check(scan.next() && scan.getString("dept").equals("math"),
            "GROUP BY without aggregates should return math");
      check(!scan.next(), "GROUP BY without aggregates should remove duplicates");
      scan.close();

      BasicQueryPlanner basic = new BasicQueryPlanner(db.mdMgr());
      Plan global = basic.createPlan(new Parser(
            "select sum(credits), count(*) from items").query(), tx);
      scan = global.open();
      check(scan.next(), "Basic planner should support global aggregation");
      check(scan.getInt("sumofcredits") == 18, "Unexpected planned SUM");
      check(scan.getInt("countofall") == 4, "Unexpected planned COUNT(*)");
      check(!scan.next(), "Global aggregate should return one row");
      scan.close();

      expectInvalidQuery(planner,
            "select dept, sum(credits) from items", tx);
      expectInvalidQuery(planner, "select sum(name) from items", tx);
   }

   private static void expectInvalidQuery(Planner planner, String sql,
                                          Transaction tx) {
      try {
         planner.createQueryPlan(sql, tx);
         throw new AssertionError("Expected invalid query: " + sql);
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static List<AggregationFn> aggregateFns() {
      return Arrays.<AggregationFn>asList(new SumFn("credits"),
            new AvgFn("credits"), new CountFn(null), new MinFn("name"),
            new MaxFn("name"));
   }

   private static Plan table(SimpleDB db, Transaction tx, String name) {
      return new TablePlan(tx, name, db.mdMgr());
   }

   private static void assertGroup(Scan scan, String dept, int sum, int avg,
                                   int count, String min, String max) {
      check(scan.getString("dept").equals(dept), "Unexpected department");
      check(scan.getInt("sumofcredits") == sum, "Unexpected group SUM");
      check(scan.getInt("avgofcredits") == avg, "Unexpected group AVG");
      check(scan.getInt("countofall") == count, "Unexpected group COUNT");
      check(scan.getString("minofname").equals(min), "Unexpected group MIN");
      check(scan.getString("maxofname").equals(max), "Unexpected group MAX");
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
