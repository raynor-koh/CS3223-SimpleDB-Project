package simpledb.test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Types;
import java.time.LocalTime;
import java.rmi.RemoteException;

import simpledb.index.planner.IndexJoinPlan;
import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.jdbc.embedded.EmbeddedDriver;
import simpledb.jdbc.network.NetworkResultSet;
import simpledb.jdbc.network.RemoteMetaData;
import simpledb.jdbc.network.RemoteResultSet;
import simpledb.materialize.MergeJoinPlan;
import simpledb.metadata.MetadataMgr;
import simpledb.multibuffer.HashJoinPlan;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.plan.*;
import simpledb.query.*;
import simpledb.record.Layout;
import simpledb.record.TableScan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Regression checks for the five TIME review findings. All databases are isolated. */
public class TimeTypeRegressionTest {
   private static int checks;

   public static void main(String[] args) throws Exception {
      testComparisonTypes();
      testRawWrites();
      testMetadataReads();
      testEmbeddedJdbc();
      testNetworkJdbc();
      System.out.println("All " + checks + " TIME regression checks passed.");
   }

   private static String databasePath() throws Exception {
      return Files.createTempDirectory("simpledb-time-regression-").resolve("database").toString();
   }

   private static void testComparisonTypes() throws Exception {
      SimpleDB db = new SimpleDB(databasePath());
      Transaction tx = db.newTx();
      try {
         Planner p = db.planner();
         p.executeUpdate("create table lefttime(lid int, lt time)", tx);
         p.executeUpdate("create table righttime(rid int, rt time)", tx);
         p.executeUpdate("create table words(wid int, txt varchar(24))", tx);
         p.executeUpdate("create index lt_hash on lefttime(lt) using hash", tx);
         p.executeUpdate("create index rt_btree on righttime(rt) using btree", tx);
         p.executeUpdate("insert into lefttime(lid, lt) values (1, '09:10:11.123456')", tx);
         p.executeUpdate("insert into righttime(rid, rt) values (2, '09:10:11.123456')", tx);
         p.executeUpdate("insert into words(wid, txt) values (3, '09:10:11.123456')", tx);
         for (JoinMethod method : JoinMethod.values()) {
            Planner planner = new Planner(new HeuristicQueryPlanner(db.mdMgr(), JoinPolicy.only(method)),
                  new IndexUpdatePlanner(db.mdMgr()));
            rejected(() -> planner.createQueryPlan(
                  "select wid, lid from words, lefttime where txt = lt", tx),
                  "Mixed TIME/VARCHAR columns must be rejected for " + method);
            check(count(planner.createQueryPlan(
                  "select lid, rid from lefttime, righttime where lt = rt", tx)) == 1,
                  "Matching TIME columns must join with " + method);
         }
         for (QueryPlanner queryPlanner : new QueryPlanner[] {
               new BasicQueryPlanner(db.mdMgr()), new BetterQueryPlanner(db.mdMgr()) }) {
            Planner planner = new Planner(queryPlanner, new BasicUpdatePlanner(db.mdMgr()));
            rejected(() -> planner.createQueryPlan(
                  "select wid, lid from words, lefttime where txt = lt", tx), "Basic planner mixed columns");
            check(count(planner.createQueryPlan(
                  "select lid from lefttime where '09:10:11.123456' = lt", tx)) == 1,
                  "Quoted TIME literals still bind on the left");
         }
         rejected(() -> p.createQueryPlan("select lid from lefttime where lt = 12", tx),
               "Integer TIME comparison must fail before execution");
         rejected(() -> p.createQueryPlan("select lid from lefttime where lt = '24:00:00'", tx),
               "Invalid TIME predicate literal must fail during planning");

         Plan times = new TablePlan(tx, "lefttime", db.mdMgr());
         Plan words = new TablePlan(tx, "words", db.mdMgr());
         Predicate equality = new Predicate(new Term(new Expression("lt"), new Expression("txt")));
         rejected(() -> new NestedLoopJoinPlan(times, words, equality), "Direct nested-loop mixed keys");
         rejected(() -> new MergeJoinPlan(tx, times, words, "lt", "txt"), "Direct merge mixed keys");
         rejected(() -> new HashJoinPlan(tx, times, words, "lt", "txt"), "Direct hash mixed keys");
         rejected(() -> new IndexJoinPlan(words, times,
               db.mdMgr().getIndexInfo("lefttime", tx).get("lt"), "txt"), "Direct index mixed keys");
         check(tx.availableBuffs() == SimpleDB.BUFFER_SIZE, "Rejected plans leaked buffers");
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void testRawWrites() throws Exception {
      SimpleDB db = new SimpleDB(databasePath());
      Transaction tx = db.newTx();
      db.planner().executeUpdate("create table rawtime(id int, tod time)", tx);
      db.planner().executeUpdate("insert into rawtime(id, tod) values (1, '01:02:03.000004')", tx);
      TableScan scan = new TableScan(tx, "rawtime", db.mdMgr().getLayout("rawtime", tx));
      try {
         check(scan.next(), "Raw TIME fixture is missing");
         long original = scan.getTimeMicros("tod");
         for (long invalid : new long[] {-1L, 86400000000L, Long.MAX_VALUE}) {
            rejected(() -> scan.setTimeMicros("tod", invalid), "Invalid raw TIME write: " + invalid);
            check(scan.getVal("tod").asTimeMicros() == original, "Rejected write modified stored TIME");
         }
         rejected(() -> scan.setTimeMicros("id", 1L), "TIME setter on integer field");
         check(scan.getInt("id") == 1, "Rejected typed write damaged adjacent integer");
         scan.setTimeMicros("tod", 86399999999L);
         check(scan.getVal("tod").equals(Constant.time("23:59:59.999999")), "Maximum raw TIME round trip");
      } finally { scan.close(); }
      tx.rollback();
   }

   private static void testMetadataReads() throws Exception {
      SimpleDB db = new SimpleDB(databasePath());
      Transaction tx = db.newTx();
      db.planner().executeUpdate("create table bulk(id int, tod time, label varchar(8))", tx);
      CountingMetadata metadata = new CountingMetadata(tx);
      for (UpdatePlanner updater : new UpdatePlanner[] {
            new BasicUpdatePlanner(metadata), new IndexUpdatePlanner(metadata) }) {
         Planner planner = new Planner(new BasicQueryPlanner(metadata), updater);
         for (int i = 0; i < 4; i++) {
            metadata.layoutReads = 0;
            planner.executeUpdate("insert into bulk(id, tod, label) values (1, '01:00:00', 'legacy')", tx);
            check(metadata.layoutReads == 1, "INSERT rescanned layout for supplied fields");
         }
         metadata.layoutReads = 0;
         planner.executeUpdate("update bulk set tod = '23:59:59.123456' where id = 1", tx);
         check(metadata.layoutReads == 1, "TIME UPDATE rescanned layout for each row");
         metadata.layoutReads = 0;
         planner.executeUpdate("update bulk set label = 'old' where id = 1", tx);
         check(metadata.layoutReads == 1, "Legacy UPDATE rescanned layout for each row");
      }
      tx.commit();
   }

   private static class CountingMetadata extends MetadataMgr {
      int layoutReads;
      CountingMetadata(Transaction tx) { super(false, tx); }
      public Layout getLayout(String table, Transaction tx) {
         layoutReads++;
         return super.getLayout(table, tx);
      }
   }

   private static void testEmbeddedJdbc() throws Exception {
      Connection connection = new EmbeddedDriver().connect("jdbc:simpledb:" + databasePath(), null);
      try {
         Statement statement = connection.createStatement();
         statement.executeUpdate("create table jt(tod time, id int)");
         statement.executeUpdate("insert into jt(tod, id) values ('09:10:11.123456', 1)");
         ResultSet result = statement.executeQuery("select tod, id from jt");
         try {
            check(result.next(), "Embedded JDBC fixture missing");
            assertJdbcTime(result);
            jdbcErrors(result);
            check(result.getObject(1, LocalTime.class).getNano() == 123456000,
                  "Invalid JDBC arguments must leave the current row readable");
         } finally { result.close(); }
      } finally { connection.close(); }
   }

   private static void testNetworkJdbc() throws Exception {
      // Exercise the client adapter and transport failures without starting an RMI server.
      RemoteFixture remote = new RemoteFixture();
      NetworkResultSet result = new NetworkResultSet(remote);
      assertJdbcTime(result);
      jdbcErrors(result);
      remote.value = "not-a-time";
      sqlRejected(() -> result.getTime("tod"), "Malformed network TIME must be SQLException");
      sqlRejected(() -> result.getObject("tod", LocalTime.class), "Network parsing must be SQLException");
      remote.fail = true;
      sqlRejected(() -> result.getTime("tod"), "Remote failures must be SQLException");
   }

   private static void assertJdbcTime(ResultSet result) throws Exception {
      LocalTime expected = LocalTime.parse("09:10:11.123456");
      check(result.getObject("tod", LocalTime.class).equals(expected), "Precise JDBC label retrieval");
      check(result.getObject(1, LocalTime.class).equals(expected), "Precise JDBC ordinal retrieval");
      Time seconds = Time.valueOf("09:10:11");
      check(result.getTime("tod").getTime() - seconds.getTime() == 123L, "Legacy JDBC must preserve 123 milliseconds");
      check(result.getTime(1).getTime() - seconds.getTime() == 123L, "Ordinal getTime must preserve milliseconds");
      check(result.getObject("tod", Time.class).getTime() - seconds.getTime() == 123L,
            "Typed legacy Time retrieval must preserve milliseconds");
   }

   private static void jdbcErrors(ResultSet result) throws Exception {
      sqlRejected(() -> result.getObject("missing", LocalTime.class), "Unknown JDBC label");
      sqlRejected(() -> result.getTime("missing"), "Unknown getTime label");
      sqlRejected(() -> result.getObject(0, LocalTime.class), "JDBC index zero");
      sqlRejected(() -> result.getTime(3), "JDBC index past last column");
      sqlRejected(() -> result.getObject("id", LocalTime.class), "Wrong source type");
      sqlRejected(() -> result.getObject("tod", (Class<LocalTime>) null), "Null requested class");
      sqlRejected(() -> result.getObject((String) null, LocalTime.class), "Null JDBC label");
   }

   private static class RemoteFixture implements RemoteResultSet {
      String value = "09:10:11.123456";
      boolean fail;
      public boolean next() { return true; }
      public int getInt(String field) { return 1; }
      public String getString(String field) throws RemoteException {
         if (fail) throw new RemoteException("Simulated transport failure");
         return value;
      }
      public void close() { }
      public RemoteMetaData getMetaData() {
         return new RemoteMetaData() {
            public int getColumnCount() { return 2; }
            public String getColumnName(int index) { return index == 1 ? "tod" : "id"; }
            public int getColumnType(int index) { return index == 1 ? Types.TIME : Types.INTEGER; }
            public int getColumnDisplaySize(int index) { return 15; }
         };
      }
   }

   private static int count(Plan plan) {
      Scan scan = plan.open();
      int count = 0;
      try { while (scan.next()) count++; }
      finally { scan.close(); }
      return count;
   }

   private static void rejected(Runnable action, String message) {
      try { action.run(); }
      catch (IllegalArgumentException expected) { checks++; return; }
      throw new AssertionError(message);
   }

   private interface SqlAction { void run() throws Exception; }

   private static void sqlRejected(SqlAction action, String message) throws Exception {
      try { action.run(); }
      catch (SQLException expected) { checks++; return; }
      throw new AssertionError(message);
   }

   private static void check(boolean condition, String message) {
      checks++;
      if (!condition) throw new AssertionError(message);
   }
}
