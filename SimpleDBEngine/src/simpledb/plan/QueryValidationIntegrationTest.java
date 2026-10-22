package simpledb.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import simpledb.opt.*;
import simpledb.parse.Parser;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Query validation must finish before physical planning opens any input. */
public class QueryValidationIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path directory = Files.createTempDirectory("simpledb-query-validation-").resolve("database");
      SimpleDB db = new SimpleDB(directory.toString());
      Transaction tx = db.newTx();
      setup(db, tx);
      try {
         db.mdMgr().getLayout("absent", tx);
         throw new AssertionError("Missing table returned a layout");
      } catch (IllegalArgumentException e) { contains(e, "Table", "absent"); }

      for (QueryPlanner qp : Arrays.asList(new BasicQueryPlanner(db.mdMgr()),
            new BetterQueryPlanner(db.mdMgr()), new HeuristicQueryPlanner(db.mdMgr()))) {
         Planner planner = new Planner(qp, new BasicUpdatePlanner(db.mdMgr()));
         invalid(planner, tx, directory, "select id from absent", "FROM", "absent");
         invalid(planner, tx, directory, "select count(*) from absent", "FROM", "absent");
         invalid(planner, tx, directory, "select id from items, absent", "FROM", "absent");
         invalid(planner, tx, directory, "select id from missingview", "FROM", "missingview");
         invalid(planner, tx, directory, "select missing from items", "SELECT", "missing");
         invalid(planner, tx, directory, "select id from items where missing = 1", "WHERE", "missing");
         invalid(planner, tx, directory, "select count(*) from items group by missing", "GROUP BY", "missing");
         invalid(planner, tx, directory, "select id from items order by missing", "ORDER BY", "missing");
         invalid(planner, tx, directory, "select label from items, others", "SELECT", "ambiguous", "label");
         invalid(planner, tx, directory, "select id from items, others where label = 'A'", "WHERE", "ambiguous", "label");
         invalid(planner, tx, directory, "select count(*) from items, others group by label", "GROUP BY", "ambiguous", "label");
         invalid(planner, tx, directory, "select id from items, others order by label", "ORDER BY", "ambiguous", "label");
         invalid(planner, tx, directory, "select id from items where id = '1'", "WHERE", "incompatible");
         invalid(planner, tx, directory, "select id from items where label = grp", "WHERE", "incompatible");
         invalid(planner, tx, directory, "select id from items where tod = id", "WHERE", "incompatible");
         invalid(planner, tx, directory, "select id from items where tod = '25:00:00'", "WHERE", "TIME", "25:00:00");
         invalid(planner, tx, directory, "select sum(label) from items", "SUM", "integer", "label");
         invalid(planner, tx, directory, "select avg(tod) from items", "AVG", "integer", "tod");
         invalid(planner, tx, directory, "select id, count(*) from items group by grp", "SELECT", "id", "GROUP BY");
         invalid(planner, tx, directory, "select id from items order by grp", "ORDER BY", "output", "grp");
         invalid(planner, tx, directory, "select id from badview", "badview", "FROM", "absent");
         invalid(planner, tx, directory, "select missing from badfield", "badfield", "SELECT", "missing");
         invalid(planner, tx, directory, "select id from loopa", "loopa", "cycle");
         invalid(planner, tx, directory, "select id from items where id = 2147483648", "WHERE", "out of range");
         invalid(planner, tx, directory, "select id from items where label = 'broken", "WHERE", "closing quote");
         invalid(planner, tx, directory, "select id from", "FROM", "identifier");

         assertValues(planner, tx, "select id from items", "id", 1, 2, 3);
         assertValues(planner, tx, "select id from v", "id", 1, 2, 3);
         assertValues(planner, tx, "select id from vv where id = 2", "id", 2);
         assertValues(planner, tx, "select id from vquoted", "id", 1, 2);
         assertValues(planner, tx, "select id from vtime", "id", 1);
         assertValues(planner, tx, "select oid, id from others, vv where oid = id", "id", 1);
         assertValues(planner, tx, "select id, oid from v, vo where id = oid", "id", 1, 3);
         assertValues(planner, tx, "select id from vjoin", "id", 1, 3);
         assertValues(planner, tx, "select grp from vg where countofall > 1", "grp", 1);
         assertValues(planner, tx, "select grp from vd", "grp", 1, 2);
         assertValues(planner, tx, "select id from items where tod = '12:00:00'", "id", 1);
         assertValues(planner, tx, "select distinct grp from items order by grp desc", "grp", 1, 2);
         check(readValues(planner, tx, "select grp from vd", "grp").equals(Arrays.asList(2, 1)),
               "A view's DISTINCT/ORDER BY was not preserved");
         assertValues(planner, tx, "select count(*) from items", "countofall", 3);
         try (Stream<Path> files = Files.list(directory)) {
            check(files.noneMatch(p -> p.getFileName().toString().matches("v.*\\.tbl")
                  && !p.getFileName().toString().equals("viewcat.tbl")),
                  "A view was opened as a physical table");
         }
         int buffs = tx.availableBuffs();
         String explanation = planner.explain("explain select id, oid from v, vo where id = oid", tx);
         check(explanation.contains("items") && explanation.contains("others"),
               "EXPLAIN did not expand the views: " + explanation);
         check(tx.availableBuffs() == buffs, "EXPLAIN leaked buffers");
      }

      // Views on both sides must work as relations under each eligible join policy.
      for (JoinMethod method : Arrays.asList(JoinMethod.HASH, JoinMethod.MERGE, JoinMethod.NESTED_LOOP)) {
         Planner planner = new Planner(new HeuristicQueryPlanner(db.mdMgr(), JoinPolicy.only(method)),
               new BasicUpdatePlanner(db.mdMgr()));
         assertValues(planner, tx, "select id, oid from v, vo where id = oid", "id", 1, 3);
         assertValues(planner, tx, "select id from vjoin", "id", 1, 3);
         String explanation = planner.explain("explain select id from vjoin", tx);
         String join = method == JoinMethod.HASH ? "HashJoin" :
               method == JoinMethod.MERGE ? "MergeJoin" : "NestedLoopJoin";
         check(explanation.contains(join), "View lost its join policy: " + explanation);
      }
      check(new QueryValidator(db.mdMgr()).validate(new Parser("select minoflabel from vmin").query(), tx)
            .length("minoflabel") == 12, "View aggregate string width was changed");
      tx.commit();
      System.out.println("All " + checksRun + " query validation integration checks passed.");
   }

   private static void setup(SimpleDB db, Transaction tx) {
      Planner planner = db.planner();
      planner.executeUpdate("create table items(id int, grp int, label varchar(12), tod time)", tx);
      planner.executeUpdate("create table others(oid int, label varchar(12))", tx);
      planner.executeUpdate("insert into items(id, grp, label, tod) values (1, 1, 'A', '12:00:00')", tx);
      planner.executeUpdate("insert into items(id, grp, label, tod) values (2, 1, 'A', '13:00:00')", tx);
      planner.executeUpdate("insert into items(id, grp, label, tod) values (3, 2, 'B', '14:00:00')", tx);
      planner.executeUpdate("insert into others(oid, label) values (1, 'X')", tx);
      planner.executeUpdate("insert into others(oid, label) values (3, 'Y')", tx);
      planner.executeUpdate("create view v as select id, grp from items", tx);
      planner.executeUpdate("create view vv as select id from v where grp = 1", tx);
      planner.executeUpdate("create view vo as select oid from others", tx);
      planner.executeUpdate("create view vg as select grp, count(*) from items group by grp", tx);
      planner.executeUpdate("create view vd as select distinct grp from items order by grp desc", tx);
      planner.executeUpdate("create view vmin as select min(label) from items", tx);
      planner.executeUpdate("create view vquoted as select id from items where label = 'A'", tx);
      planner.executeUpdate("create view vtime as select id from items where tod = '12:00:00'", tx);
      planner.executeUpdate("create view vjoin as select id, oid from v, vo where id = oid", tx);
      db.mdMgr().createView("badview", "select id from absent", tx);
      db.mdMgr().createView("badfield", "select missing from items", tx);
      db.mdMgr().createView("loopa", "select id from loopb", tx);
      db.mdMgr().createView("loopb", "select id from loopa", tx);
   }

   private static void invalid(Planner planner, Transaction tx, Path directory,
                               String sql, String... fragments) throws Exception {
      Set<String> before = fileNames(directory);
      int buffers = tx.availableBuffs();
      for (boolean explain : new boolean[] {false, true}) {
         try {
            if (explain) planner.explain("explain " + sql, tx);
            else planner.createQueryPlan(sql, tx);
            throw new AssertionError("Invalid query accepted before open(): " + sql);
         } catch (IllegalArgumentException e) {
            contains(e, fragments);
            if (sql.equals("select count(*) from absent"))
               check(e.getCause() != null, "Missing table lost its metadata cause");
         }
         check(tx.availableBuffs() == buffers, "Invalid query leaked buffers: " + sql);
         check(before.equals(fileNames(directory)), "Invalid query created a file: " + sql);
      }
   }

   private static Set<String> fileNames(Path directory) throws Exception {
      try (Stream<Path> files = Files.list(directory)) {
         return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
      }
   }

   private static void assertValues(Planner planner, Transaction tx, String sql,
                                    String field, Integer... expected) {
      List<Integer> actual = readValues(planner, tx, sql, field);
      Collections.sort(actual);
      check(actual.equals(Arrays.asList(expected)), "Wrong result for " + sql + ": " + actual);
   }

   private static List<Integer> readValues(Planner planner, Transaction tx, String sql, String field) {
      int buffers = tx.availableBuffs();
      List<Integer> actual = new ArrayList<Integer>();
      Scan scan = planner.createQueryPlan(sql, tx).open();
      try { while (scan.next()) actual.add(scan.getInt(field)); }
      finally { scan.close(); }
      check(tx.availableBuffs() == buffers, "Query leaked buffers: " + sql);
      return actual;
   }

   private static void contains(Exception e, String... fragments) {
      check(e.getMessage() != null, "Missing query diagnostic");
      for (String fragment : fragments)
         check(e.getMessage().contains(fragment), "Expected '" + fragment + "' in " + e.getMessage());
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition) throw new AssertionError(message);
   }
}
