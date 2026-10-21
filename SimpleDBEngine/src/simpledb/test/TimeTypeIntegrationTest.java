package simpledb.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import simpledb.index.Index;
import simpledb.metadata.IndexInfo;
import simpledb.metadata.MetadataMgr;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;
import simpledb.jdbc.embedded.EmbeddedDriver;

/** End-to-end coverage for TIME storage, query, index, and JDBC paths. */
public class TimeTypeIntegrationTest {
   private static int checks;

   public static void main(String[] args) throws Exception {
      Path root = Files.createTempDirectory("simpledb-time-");
      String dbname = root.resolve("database").toString();
      createDatabase(dbname);
      testReopenQueriesAndIndexes(dbname);
      testJdbc(dbname);
      testTimeValidation();
      System.out.println("All " + checks + " TIME integration checks passed.");
   }

   private static void createDatabase(String dbname) {
      SimpleDB db = new SimpleDB(dbname);
      Planner planner = db.planner();
      Transaction tx = db.newTx();
      try {
         planner.executeUpdate("create table times_h(id int, tod time, note varchar(12))", tx);
         planner.executeUpdate("create table times_b(id int, tod time, note varchar(12))", tx);
         planner.executeUpdate("create index time_hash on times_h(tod) using hash", tx);
         planner.executeUpdate("create index time_btree on times_b(tod) using btree", tx);
         planner.executeUpdate("create table legacy(id int, label varchar(8))", tx);
         insertRows(planner, tx, "times_h");
         insertRows(planner, tx, "times_b");
         planner.executeUpdate("insert into legacy(id, label) values (7, 'legacy')", tx);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void insertRows(Planner planner, Transaction tx, String table) {
      planner.executeUpdate("insert into " + table + "(id, tod, note) values (1, '00:00:00', 'midnight')", tx);
      planner.executeUpdate("insert into " + table + "(id, tod, note) values (2, '12:34:56.123456', 'fraction')", tx);
      planner.executeUpdate("insert into " + table + "(id, tod, note) values (3, '23:59:59.999999', 'last')", tx);
      planner.executeUpdate("insert into " + table + "(id, tod, note) values (4, '12:34:56.123456', 'duplicate')", tx);
   }

   private static void testReopenQueriesAndIndexes(String dbname) {
      SimpleDB db = new SimpleDB(dbname);
      Planner planner = db.planner();
      MetadataMgr metadata = db.mdMgr();
      Transaction tx = db.newTx();
      try {
         check(metadata.getLayout("times_h", tx).schema().type("tod") == java.sql.Types.TIME,
               "TIME catalog type did not survive reopen");
         check(metadata.getLayout("times_h", tx).slotSize() == 32,
               "Reopened TIME record layout has an incorrect fixed width");
         checkIndex(metadata, tx, "times_h", Constant.time("12:34:56.123456"), 2);
         checkIndex(metadata, tx, "times_b", Constant.time("23:59:59.999999"), 1);

         assertIds(planner, tx, "select id, tod from times_b where tod > '12:00:00' order by tod, id", 2, 4, 3);
         assertIds(planner, tx, "select id from times_b where tod = '12:34:56.123456' order by id", 2, 4);
         assertTimes(planner, tx, "select distinct tod from times_b order by tod",
               "00:00:00", "12:34:56.123456", "23:59:59.999999");
         assertIds(planner, tx, "select id from times_b where tod >= '23:59:59.999999'", 3);
         assertIds(planner, tx, "select id from times_b where tod < '00:00:00.000001'", 1);
         assertIds(planner, tx, "select id from times_b where tod <> '12:34:56.123456' order by id", 1, 3);
         assertIds(planner, tx, "select id from legacy where id = 7", 7);

         planner.executeUpdate("update times_b set tod = '01:02:03.000004' where id = 1", tx);
         assertTimes(planner, tx, "select tod from times_b where id = 1",
               "01:02:03.000004");
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void testJdbc(String dbname) throws Exception {
      Connection connection = new EmbeddedDriver().connect("jdbc:simpledb:" + dbname, null);
      try {
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("select tod from times_b where id = 1");
         check(result.next(), "JDBC did not return TIME row");
         LocalTime expected = LocalTime.of(1, 2, 3, 4000);
         check(expected.equals(result.getObject("tod", LocalTime.class)),
               "Typed JDBC LocalTime retrieval lost microseconds");
         check("01:02:03.000004".equals(result.getString("tod")),
               "JDBC string form did not preserve fractional TIME digits");
         check(result.getTime("tod") != null,
               "JDBC compatibility getTime() returned null");
         ResultSetMetaData meta = result.getMetaData();
         check(meta.getColumnType(1) == java.sql.Types.TIME, "JDBC metadata did not report Types.TIME");
         check("TIME".equals(meta.getColumnTypeName(1)), "JDBC metadata did not report TIME name");
         check(meta.getScale(1) == 6, "JDBC metadata did not report microsecond scale");
         result.close();
      } finally {
         connection.close();
      }
   }

   private static void testTimeValidation() {
      check(Constant.time("00:00:00").asTimeMicros() == 0L, "Midnight must be zero");
      check(Constant.time("23:59:59.999999").asTimeMicros() == 86399999999L,
            "Last microsecond of day is incorrect");
      for (String invalid : Arrays.asList("24:00:00", "12:60:00", "12:00:60",
            "-01:00:00", "12:00", "12:00:00.1234567", "not-a-time")) {
         expectInvalidTime(invalid);
      }
      expectInvalidTime("00:00:00.0000001");
   }

   private static void expectInvalidTime(String input) {
      boolean rejected = false;
      try { Constant.time(input); }
      catch (IllegalArgumentException expected) { rejected = true; }
      check(rejected, "Invalid TIME was accepted: " + input);
   }

   private static void checkIndex(MetadataMgr metadata, Transaction tx, String table,
         Constant key, int expected) {
      IndexInfo info = metadata.getIndexInfo(table, tx).get("tod");
      check(info != null, "Missing TIME index metadata for " + table);
      Index index = info.open();
      int count = 0;
      try {
         index.beforeFirst(key);
         while (index.next()) count++;
      } finally { index.close(); }
      check(count == expected, table + " TIME index expected " + expected + " hits, got " + count);
   }

   private static void assertIds(Planner planner, Transaction tx, String sql, int... expected) {
      Plan plan = planner.createQueryPlan(sql, tx);
      Scan scan = plan.open();
      List<Integer> actual = new ArrayList<Integer>();
      while (scan.next()) actual.add(scan.getInt("id"));
      scan.close();
      List<Integer> expectedList = new ArrayList<Integer>();
      for (int id : expected) expectedList.add(id);
      check(expectedList.equals(actual), sql + " expected " + expectedList + ", got " + actual);
   }

   private static void assertTimes(Planner planner, Transaction tx, String sql, String... expected) {
      Plan plan = planner.createQueryPlan(sql, tx);
      Scan scan = plan.open();
      List<String> actual = new ArrayList<String>();
      String field = plan.schema().fields().get(0);
      while (scan.next()) actual.add(scan.getVal(field).toString());
      scan.close();
      check(Arrays.asList(expected).equals(actual), sql + " expected " + Arrays.asList(expected) + ", got " + actual);
   }

   private static void check(boolean condition, String message) {
      checks++;
      if (!condition) throw new AssertionError(message);
   }
}
