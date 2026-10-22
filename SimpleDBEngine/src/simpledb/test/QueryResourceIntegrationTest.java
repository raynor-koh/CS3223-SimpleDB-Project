package simpledb.test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.multibuffer.HashJoinPlan;
import simpledb.multibuffer.HashJoinScan;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.parse.Parser;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.plan.TablePlan;
import simpledb.query.Scan;
import simpledb.record.Schema;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Bounded scan-lifecycle, external-workload, and non-executing EXPLAIN checks. */
public class QueryResourceIntegrationTest {
    private static final int TIMEOUT_SECONDS = 120;
    private static final int STRESS_ROWS = 24;
    private static int checks;

    public static void main(String[] args) throws Exception {
        FutureTask<Integer> task = new FutureTask<>(QueryResourceIntegrationTest::runChecks);
        Thread worker = new Thread(task, "query-resource-checks");
        // A stuck engine operation cannot keep this standalone test JVM alive.
        worker.setDaemon(true);
        worker.start();
        try {
            int total = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.println("All " + total + " query-resource integration checks passed.");
        } catch (TimeoutException e) {
            for (StackTraceElement frame : worker.getStackTrace())
                System.err.println("  at " + frame);
            throw new AssertionError("Resource suite exceeded " + TIMEOUT_SECONDS + " seconds", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error)
                throw (Error) e.getCause();
            throw (Exception) e.getCause();
        }
    }

    private static int runChecks() throws Exception {
        Path directory = Files.createTempDirectory("simpledb-query-resources-").resolve("database");
        System.out.println("Resource fixture: " + directory);
        SimpleDB seed = new SimpleDB(directory.toString());
        Transaction setup = seed.newTx();
        try {
            JoinFeatureFixture.create(seed.planner(), setup);
            createResourceFixture(seed.planner(), setup);
            setup.commit();
        } catch (RuntimeException | Error e) {
            setup.rollback();
            throw e;
        }
        SimpleDB db = new SimpleDB(directory.toString());
        Transaction tx = db.newTx();
        try {
            check(tx.availableBuffs() == SimpleDB.BUFFER_SIZE, "Fixture must start with every buffer available");
            for (JoinMethod method : JoinMethod.values()) {
                System.out.println("Checking scan lifecycle and EXPLAIN: " + method);
                testLifecycle(db, tx, method);
                testExplain(db, tx, directory, method);
            }
            System.out.println("Checking multi-block sorting and skewed hash partitioning");
            testSpillAndSkew(db, tx, directory);
            check(tx.availableBuffs() == SimpleDB.BUFFER_SIZE, "All buffers must be released before commit");
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
        return checks;
    }

    private static void createResourceFixture(Planner planner, Transaction tx) {
        for (String sql : Arrays.asList(
                "create table resempty(eid int, ekey int)",
                "create table resemptytwo(fid int, fkey int)",
                "create table resnomatch(nid int, nkey int)",
                "create index res_ekey on resempty(ekey) using hash",
                "create index res_fkey on resemptytwo(fkey) using btree",
                "create index res_nkey on resnomatch(nkey) using hash",
                "insert into resnomatch(nid, nkey) values (99, 999)",
                "create table reswideleft(wlid int, wlkey int, wlpad varchar(200))",
                "create table reswideright(wrid int, wrkey int, wrpad varchar(200))"))
            planner.executeUpdate(sql, tx);
        // Fixed-width strings make each 24-row input larger than the eight
        // buffers even though actual string contents are short. Descending IDs
        // produce many natural sort runs. A single key concentrates hash data.
        for (int i = STRESS_ROWS; i >= 1; i--) {
            planner.executeUpdate("insert into reswideleft(wlid, wlkey, wlpad) values ("
                    + i + ", 2, 'left')", tx);
            planner.executeUpdate("insert into reswideright(wrid, wrkey, wrpad) values ("
                    + (100 + i) + ", 2, 'right')", tx);
        }
    }

    private static void testLifecycle(SimpleDB db, Transaction tx, JoinMethod method) {
        Planner planner = planner(db, method);
        String pair = "select aid, atag, bid from l7a, l7b where ak = bk";
        List<String> pairFields = new Parser(pair).query().fields();
        exercise(planner.createQueryPlan(pair, tx), pairFields,
                QueryResultAssertions.bag(JoinFeatureFixture.expected(false, true, row -> true, pairFields)),
                tx, method + " joined projection");

        String chain = "select aid, bid, cid, did from " + JoinFeatureFixture.FOUR_FROM
                + " where " + JoinFeatureFixture.FOUR_JOIN;
        List<String> chainFields = new Parser(chain).query().fields();
        exercise(planner.createQueryPlan(chain, tx), chainFields,
                QueryResultAssertions.bag(JoinFeatureFixture.expected(true, true, row -> true, chainFields)),
                tx, method + " four-table chain");

        String distinct = "select distinct aid, atag from l7a, l7b where ak = bk order by aid desc";
        exercise(planner.createQueryPlan(distinct, tx), Arrays.asList("aid", "atag"),
                bag(new Object[] {1, "amy"}, new Object[] {2, "bob"}), tx, method + " distinct/order");
        String aggregate = combinedQuery();
        exercise(planner.createQueryPlan(aggregate, tx), Arrays.asList("agrp", "countofall"),
                bag(new Object[] {1, 3}, new Object[] {2, 3}), tx, method + " aggregate/distinct/order");

        for (String sql : Arrays.asList(
                "select aid, eid from l7a, resempty where ak = ekey",
                "select eid, aid from resempty, l7a where ekey = ak",
                "select eid, fid from resempty, resemptytwo where ekey = fkey",
                "select aid, nid from l7a, resnomatch where ak = nkey")) {
            exercise(planner.createQueryPlan(sql, tx), new Parser(sql).query().fields(),
                    Collections.emptyMap(), tx, method + " empty/no-match: " + sql);
        }
    }

    private static void exercise(Plan plan, List<String> fields, Map<List<Object>, Integer> expected,
                                  Transaction tx, String label) {
        // Reopen the same physical plan, checking before commit can mask leaks.
        for (int round = 0; round < 3; round++) {
            if (round < 2) {
                int before = tx.availableBuffs();
                Scan scan = plan.open();
                try {
                    if (round == 1) {
                        boolean found = scan.next();
                        check(found == !expected.isEmpty(), label + ": early row availability");
                        if (found)
                            for (String field : fields)
                                scan.getVal(field);
                    }
                } finally {
                    scan.close();
                }
                check(tx.availableBuffs() == before, label + ": early close leaked buffers");
            }
            int before = tx.availableBuffs();
            QueryResultAssertions.assertBagEquals(label + " round " + round, expected,
                    QueryResultAssertions.readBag(plan, fields));
            checks++;
            check(tx.availableBuffs() == before, label + ": full consumption leaked buffers");
        }
    }

    private static void testExplain(SimpleDB db, Transaction tx, Path directory, JoinMethod method)
            throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<Plan> actual = new AtomicReference<>();
        HeuristicQueryPlanner queryPlanner = new HeuristicQueryPlanner(db.mdMgr(), JoinPolicy.only(method));
        Planner guarded = new Planner((data, transaction) -> {
            Plan plan = queryPlanner.createPlan(data, transaction);
            actual.set(plan);
            return new OpenGuard(plan, opens);
        }, new IndexUpdatePlanner(db.mdMgr()));
        Set<String> beforeFiles = temporaryFiles(directory);
        int beforeBuffers = tx.availableBuffs();
        String first = guarded.explain("explain " + combinedQuery(), tx);
        String second = guarded.explain("explain " + combinedQuery(), tx);
        check(first.equals(second), method + ": repeated EXPLAIN must be stable");
        check(first.contains("GroupBy") && first.contains("Distinct") && first.contains("Sort"),
                method + ": combined EXPLAIN must expose materializing stages");
        check(opens.get() == 0, method + ": EXPLAIN opened an execution scan");
        check(temporaryFiles(directory).equals(beforeFiles), method + ": EXPLAIN created temporary tables");
        check(tx.availableBuffs() == beforeBuffers, method + ": EXPLAIN leaked buffers");

        // Positive control: the unguarded plan actually executes and creates
        // temporary files, so the artifact check can detect materialization.
        QueryResultAssertions.assertBagEquals(method + " EXPLAIN positive control",
                bag(new Object[] {1, 3}, new Object[] {2, 3}),
                QueryResultAssertions.readBag(actual.get(), Arrays.asList("agrp", "countofall")));
        checks++;
        check(temporaryFiles(directory).size() > beforeFiles.size(),
                method + ": execution must exercise temporary-table creation");
        check(tx.availableBuffs() == beforeBuffers, method + ": positive-control execution leaked buffers");
    }

    private static void testSpillAndSkew(SimpleDB db, Transaction tx, Path directory) throws Exception {
        int buffers = tx.availableBuffs();
        check(tx.size("reswideleft.tbl") > buffers && tx.size("reswideright.tbl") > buffers,
                "Both stress inputs must occupy more blocks than the available buffers");
        Planner hashPlanner = planner(db, JoinMethod.HASH);
        String sorted = "select wlid, wlpad from reswideleft order by wlid";
        List<List<Object>> sortedRows = new ArrayList<>();
        for (int i = 1; i <= STRESS_ROWS; i++)
            sortedRows.add(Arrays.asList(i, "left"));
        Set<String> files = temporaryFiles(directory);
        Plan sortPlan = hashPlanner.createQueryPlan(sorted, tx);
        int beforeSort = tx.availableBuffs();
        List<List<Object>> actualOrder = new ArrayList<>();
        Scan sortedScan = sortPlan.open();
        try {
            while (sortedScan.next())
                actualOrder.add(Arrays.asList(sortedScan.getInt("wlid"), sortedScan.getString("wlpad")));
        } finally {
            sortedScan.close();
        }
        check(actualOrder.equals(sortedRows), "Multi-block sorting must produce the exact ascending sequence");
        check(tx.availableBuffs() == beforeSort, "Multi-block sort must release its buffers");
        check(temporaryFiles(directory).size() - files.size() > STRESS_ROWS,
                "One execution must create natural runs and intermediate merge tables");
        exercise(sortPlan, Arrays.asList("wlid", "wlpad"),
                QueryResultAssertions.bag(sortedRows), tx, "multi-block sort");

        List<List<Object>> pairs = new ArrayList<>();
        for (int left = 1; left <= STRESS_ROWS; left++)
            for (int right = 1; right <= STRESS_ROWS; right++)
                pairs.add(Arrays.asList(left, 100 + right));
        Plan skew = new HashJoinPlan(tx, new TablePlan(tx, "reswideleft", db.mdMgr()),
                new TablePlan(tx, "reswideright", db.mdMgr()), "wlkey", "wrkey");
        exercise(skew, Arrays.asList("wlid", "wrid"), QueryResultAssertions.bag(pairs), tx,
                "oversized single-key hash partition");

        // Inspect actual execution rather than inferring skew fallback from
        // the planned HashJoin node. No production counters are introduced.
        int before = tx.availableBuffs();
        HashJoinScan scan = (HashJoinScan) skew.open();
        try {
            check(scan.next(), "Skewed hash join must produce its first match");
            Field fallback = HashJoinScan.class.getDeclaredField("fallbackScan");
            fallback.setAccessible(true);
            check(fallback.get(scan) != null, "Oversized skew must exercise the bounded nested-loop fallback");
        } finally {
            scan.close();
        }
        check(tx.availableBuffs() == before, "Early closing runtime fallback must release buffers");
    }

    private static Set<String> temporaryFiles(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("temp[0-9]+\\.tbl")).collect(Collectors.toSet());
        }
    }

    private static String combinedQuery() {
        return "select distinct agrp, count(*) from l7a, l7b where ak = bk "
                + "group by agrp order by agrp desc";
    }

    private static Planner planner(SimpleDB db, JoinMethod method) {
        return new Planner(new HeuristicQueryPlanner(db.mdMgr(), JoinPolicy.only(method)),
                new IndexUpdatePlanner(db.mdMgr()));
    }

    private static Map<List<Object>, Integer> bag(Object[]... tuples) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] tuple : tuples)
            rows.add(Arrays.asList(tuple));
        return QueryResultAssertions.bag(rows);
    }

    /** Forbid opening the root or any child visited while displaying a real plan. */
    private static class OpenGuard implements Plan {
        private final Plan delegate;
        private final AtomicInteger opens;

        OpenGuard(Plan delegate, AtomicInteger opens) {
            this.delegate = delegate;
            this.opens = opens;
        }

        public Scan open() {
            opens.incrementAndGet();
            throw new AssertionError("EXPLAIN opened " + delegate.operationDescription());
        }

        public int blocksAccessed() { return delegate.blocksAccessed(); }
        public int recordsOutput() { return delegate.recordsOutput(); }
        public int distinctValues(String field) { return delegate.distinctValues(field); }
        public Schema schema() { return delegate.schema(); }
        public String operationDescription() { return delegate.operationDescription(); }
        public List<Plan> children() {
            List<Plan> children = new ArrayList<>();
            for (Plan child : delegate.children())
                children.add(new OpenGuard(child, opens));
            return children;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}
