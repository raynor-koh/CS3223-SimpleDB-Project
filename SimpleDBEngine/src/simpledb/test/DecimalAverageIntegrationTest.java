package simpledb.test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.rmi.RemoteException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.jdbc.embedded.EmbeddedDriver;
import simpledb.jdbc.network.NetworkResultSet;
import simpledb.jdbc.network.RemoteMetaData;
import simpledb.jdbc.network.RemoteResultSet;
import simpledb.materialize.MaterializePlan;
import simpledb.multibuffer.ChunkScan;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.parse.Parser;
import simpledb.plan.BasicQueryPlanner;
import simpledb.plan.BetterQueryPlanner;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.plan.QueryValidator;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.Layout;
import simpledb.record.Schema;
import simpledb.record.TableScan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Decimal AVG through grouping, external sorting, materialization, views and JDBC. */
public class DecimalAverageIntegrationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testConstants();
        String directory = Files.createTempDirectory("simpledb-decimal-average-").resolve("database").toString();
        SimpleDB db = new SimpleDB(directory);
        Transaction tx = db.newTx();
        try {
            createFixture(db.planner(), tx);
            testRecordStorage(tx);
            for (Planner planner : Arrays.asList(
                    new Planner(new BasicQueryPlanner(db.mdMgr()), new IndexUpdatePlanner(db.mdMgr())),
                    new Planner(new BetterQueryPlanner(db.mdMgr()), new IndexUpdatePlanner(db.mdMgr())),
                    new Planner(new HeuristicQueryPlanner(db.mdMgr()), new IndexUpdatePlanner(db.mdMgr())))) {
                testRounding(planner, tx);
                testSorting(planner, tx);
                testViews(db, planner, tx);
            }
            check(tx.availableBuffs() == SimpleDB.BUFFER_SIZE, "Decimal queries leaked buffers");
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
        testEmbeddedJdbc(directory);
        testNetworkAdapter();
        System.out.println("All " + checks + " decimal-average integration checks passed.");
    }

    private static void testConstants() {
        Constant a = Constant.fromDecimal(new BigDecimal("1.5"));
        Constant b = Constant.fromDecimal(new BigDecimal("1.500000"));
        check(a.type() == Types.DECIMAL && a.toString().equals("1.500000"), "Decimal type and formatting");
        check(a.equals(b) && b.equals(a) && a.hashCode() == b.hashCode(), "Canonical decimal equality/hash");
        check(a.compareTo(Constant.fromDecimal(new BigDecimal("10.5"))) < 0, "Numeric decimal comparison");
        check(!a.equals(new Constant("1.500000")), "DECIMAL must remain distinct from VARCHAR");
        rejected(() -> Constant.fromDecimal(new BigDecimal("10000000000")), "Decimal precision overflow");
        rejected(() -> Constant.fromDecimal(new BigDecimal("0.0000001")), "Unrounded decimal precision loss");
    }

    private static void createFixture(Planner planner, Transaction tx) {
        planner.executeUpdate("create table decinput(gid int, amount int)", tx);
        int[][] cases = {
            {1, 1, 2}, {2, -1, -2}, {3, 0, 0, 1}, {4, 0, 0, -1},
            {5, Integer.MAX_VALUE, Integer.MAX_VALUE}, {6, Integer.MIN_VALUE, Integer.MIN_VALUE},
            {7, Integer.MIN_VALUE, Integer.MAX_VALUE}
        };
        for (int[] row : cases)
            for (int i = 1; i < row.length; i++) insert(planner, tx, "decinput", row[0], row[i]);
        // Exactly +/-1/128 exercises a HALF_UP tie at the seventh decimal place.
        for (int i = 0; i < 128; i++) {
            insert(planner, tx, "decinput", 8, i == 0 ? 1 : 0);
            insert(planner, tx, "decinput", 9, i == 0 ? -1 : 0);
        }
        planner.executeUpdate("create table decsort(gid int, amount int)", tx);
        for (int group = 121; group >= 0; group--) {
            int value = (group * 17 % 61) - 30;
            insert(planner, tx, "decsort", group, value);
            insert(planner, tx, "decsort", group, value + 1);
        }
        planner.executeUpdate("create view decview as select gid, avg(amount) from decsort group by gid", tx);
    }

    private static void insert(Planner planner, Transaction tx, String table, int group, int value) {
        planner.executeUpdate("insert into " + table + "(gid, amount) values (" + group + ", " + value + ")", tx);
    }

    private static void testRounding(Planner planner, Transaction tx) {
        String[] expected = {"1.500000", "-1.500000", "0.333333", "-0.333333", "2147483647.000000",
            "-2147483648.000000", "-0.500000", "0.007813", "-0.007813"};
        for (int i = 0; i < expected.length; i++) {
            String sql = "select avg(amount) from decinput where gid = " + (i + 1);
            Plan plan = planner.createQueryPlan(sql, tx);
            check(plan.schema().type("avgofamount") == Types.DECIMAL, "AVG output schema must be DECIMAL");
            assertRows(plan, Arrays.asList("avgofamount"),
                    Arrays.asList(Arrays.<Object>asList(new BigDecimal(expected[i]))), sql);
        }
    }

    private static List<List<Object>> sortedRows() {
        List<List<Object>> rows = new ArrayList<>();
        for (int group = 0; group < 122; group++)
            rows.add(Arrays.asList(group, BigDecimal.valueOf(group * 17 % 61 - 30)
                    .add(new BigDecimal("0.500000"))));
        rows.sort(Comparator.<List<Object>, BigDecimal>comparing(row -> (BigDecimal) row.get(1))
                .thenComparingInt(row -> (Integer) row.get(0)));
        return rows;
    }

    private static void testSorting(Planner planner, Transaction tx) {
        String sql = "select gid, avg(amount) from decsort group by gid order by avgofamount, gid";
        Plan plan = planner.createQueryPlan(sql, tx);
        List<List<Object>> expected = sortedRows();
        int before = tx.availableBuffs();
        assertRows(plan, Arrays.asList("gid", "avgofamount"), expected, "Numeric AVG external sort");
        assertRows(new MaterializePlan(tx, plan), Arrays.asList("gid", "avgofamount"), expected,
                "Materialized decimal rows");
        Scan scan = plan.open();
        try {
            for (int pass = 0; pass < 2; pass++) {
                int count = 0;
                while (scan.next()) {
                    check(scan.getVal("avgofamount").asDecimal().equals(expected.get(count).get(1)),
                            "Decimal sort rewind changed a value");
                    count++;
                }
                check(count == 122, "Decimal sort rewind lost rows");
                scan.beforeFirst();
            }
        } finally { scan.close(); }
        List<List<Object>> distinct = new ArrayList<>();
        for (int i = 30; i >= -30; i--)
            distinct.add(Arrays.<Object>asList(BigDecimal.valueOf(i).add(new BigDecimal("0.500000"))));
        String distinctSql = "select distinct avg(amount) from decsort group by gid order by avgofamount desc";
        assertRows(planner.createQueryPlan(distinctSql, tx), Arrays.asList("avgofamount"), distinct,
                "Decimal DISTINCT and descending order");
        check(tx.availableBuffs() == before, "Decimal sort/materialization leaked buffers");
    }

    private static void testViews(SimpleDB db, Planner planner, Transaction tx) {
        String sql = "select gid, avgofamount from decview order by avgofamount, gid";
        Schema logical = new QueryValidator(db.mdMgr()).validate(new Parser(sql).query(), tx);
        check(logical.type("avgofamount") == Types.DECIMAL, "View validation lost decimal output type");
        assertRows(planner.createQueryPlan(sql, tx), Arrays.asList("gid", "avgofamount"), sortedRows(),
                "Decimal view output");
        String extrema = "select min(avgofamount), max(avgofamount) from decview";
        assertRows(planner.createQueryPlan(extrema, tx), Arrays.asList("minofavgofamount", "maxofavgofamount"),
                Arrays.asList(Arrays.<Object>asList(new BigDecimal("-29.500000"), new BigDecimal("30.500000"))),
                "MIN/MAX must preserve decimal view values");
    }

    private static void testRecordStorage(Transaction tx) {
        Schema schema = new Schema();
        schema.addIntField("id");
        schema.addDecimalField("value");
        Layout layout = new Layout(schema);
        check(layout.slotSize() == 16, "Decimal result storage must occupy eight bytes");
        int before = tx.availableBuffs();
        TableScan table = new TableScan(tx, "decrecords", layout);
        try {
            for (int i = 0; i < 80; i++) {
                table.insert();
                table.setInt("id", i);
                table.setVal("value", Constant.fromDecimal(new BigDecimal(i % 2 == 0
                        ? "9999999999.999999" : "-9999999999.999999")));
            }
            rejected(() -> table.setVal("value", new Constant(1)), "Reject a nondecimal typed write");
            check(table.getInt("id") == 79, "Rejected decimal write damaged an adjacent field");
        } finally { table.close(); }
        // Reading through a different scan exercises stored bytes, not aggregate state.
        Scan scan = new ChunkScan(tx, "decrecords.tbl", layout, 0, tx.size("decrecords.tbl") - 1);
        try {
            int count = 0;
            while (scan.next()) {
                check(scan.getInt("id") == count, "Decimal record layout damaged an adjacent field");
                check(scan.getVal("value").toString().equals(count % 2 == 0
                        ? "9999999999.999999" : "-9999999999.999999"), "Decimal storage round trip");
                count++;
            }
            check(count == 80, "Decimal chunk scan lost rows");
        } finally { scan.close(); }
        check(tx.availableBuffs() == before, "Decimal record/chunk scans leaked buffers");
    }

    private static void testEmbeddedJdbc(String directory) throws Exception {
        try (Connection connection = new EmbeddedDriver().connect("jdbc:simpledb:" + directory, null);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table decjdbc(label varchar(8), amount int)");
            statement.executeUpdate("insert into decjdbc(label, amount) values ('same', 1)");
            statement.executeUpdate("insert into decjdbc(label, amount) values ('same', 2)");
            try (ResultSet result = statement.executeQuery(
                    "select label, avg(amount) from decjdbc group by label order by avgofamount")) {
                check(result.next(), "Embedded decimal result missing");
                assertJdbcDecimal(result);
                check(!result.next(), "Embedded AVG should return one group");
            }
        }
    }

    private static void testNetworkAdapter() throws Exception {
        RemoteFixture remote = new RemoteFixture();
        try (ResultSet result = new NetworkResultSet(remote)) {
            assertJdbcDecimal(result);
            check(remote.metadataReads == 1, "Numeric getters must cache remote column types");
            remote.value = "invalid";
            sqlRejected(() -> result.getBigDecimal(2), "Malformed remote decimal must be SQLException");
            remote.value = "1.500000";
            remote.fail = true;
            sqlRejected(() -> result.getBigDecimal(2), "Remote decimal failure must be SQLException");
        }
    }

    @SuppressWarnings("deprecation")
    private static void assertJdbcDecimal(ResultSet result) throws Exception {
        BigDecimal expected = new BigDecimal("1.500000");
        check(result.getBigDecimal("AVGOFAMOUNT").equals(expected), "JDBC decimal label retrieval");
        check(result.getBigDecimal(2).equals(expected), "JDBC decimal ordinal retrieval");
        check(result.getBigDecimal(2, 2).equals(new BigDecimal("1.50")), "JDBC explicit scale");
        check(result.getObject("avgofamount").equals(expected), "JDBC untyped decimal object");
        check(result.getObject(2).equals(expected), "JDBC ordinal decimal object");
        check(result.getObject("avgofamount", BigDecimal.class).equals(expected), "JDBC typed decimal label");
        check(result.getObject(2, BigDecimal.class).equals(expected), "JDBC typed decimal ordinal");
        check(result.getString("avgofamount").equals("1.500000")
                && result.getString(2).equals("1.500000"), "JDBC decimal formatting");
        check(result.getDouble(2) == 1.5 && result.getDouble("avgofamount") == 1.5, "JDBC double conversion");
        check(result.getInt(2) == 1, "An explicit JDBC integer conversion truncates fractions");
        ResultSetMetaData metadata = result.getMetaData();
        check(metadata.getColumnType(2) == Types.DECIMAL, "JDBC decimal type");
        check(metadata.getColumnTypeName(2).equals("DECIMAL"), "JDBC decimal type name");
        check(metadata.getColumnClassName(2).equals(BigDecimal.class.getName()), "JDBC decimal Java class");
        check(metadata.getPrecision(2) == 16 && metadata.getScale(2) == 6, "JDBC decimal precision/scale");
        check(metadata.getColumnDisplaySize(2) >= 18, "JDBC decimal display width");
        sqlRejected(() -> result.getBigDecimal("missing"), "Unknown decimal column label");
        sqlRejected(() -> result.getBigDecimal((String) null), "Null decimal column label");
        sqlRejected(() -> result.getBigDecimal(0), "Decimal ordinal zero");
        sqlRejected(() -> result.getBigDecimal(3), "Decimal ordinal past the last column");
        sqlRejected(() -> result.getBigDecimal("label"), "Nonnumeric decimal source");
        check(result.getBigDecimal(2).equals(expected), "Invalid arguments must leave the row readable");
    }

    private static class RemoteFixture implements RemoteResultSet {
        String value = "1.500000";
        boolean fail;
        int metadataReads;
        public boolean next() { return true; }
        public int getInt(String field) { throw new AssertionError("Decimal must not use remote integer getter"); }
        public String getString(String field) throws RemoteException {
            if (fail) throw new RemoteException("Simulated transport failure");
            return field.equalsIgnoreCase("label") ? "same" : value;
        }
        public void close() { }
        public RemoteMetaData getMetaData() {
            metadataReads++;
            return new RemoteMetaData() {
                public int getColumnCount() { return 2; }
                public String getColumnName(int index) { return index == 1 ? "label" : "avgofamount"; }
                public int getColumnType(int index) { return index == 1 ? Types.VARCHAR : Types.DECIMAL; }
                public int getColumnDisplaySize(int index) { return 19; }
            };
        }
    }

    private static void assertRows(Plan plan, List<String> fields, List<List<Object>> expected, String context) {
        QueryResultAssertions.assertSequenceEquals(context, expected, QueryResultAssertions.readRows(plan, fields));
        checks++;
    }

    private interface Action { void run() throws Exception; }

    private static void sqlRejected(Action action, String message) throws Exception {
        try { action.run(); }
        catch (SQLException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void rejected(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException | ArithmeticException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
