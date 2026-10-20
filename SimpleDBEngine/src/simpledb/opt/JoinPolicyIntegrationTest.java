package simpledb.opt;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import simpledb.file.BlockId;
import simpledb.index.planner.IndexJoinPlan;
import simpledb.index.planner.IndexSelectPlan;
import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.materialize.MergeJoinPlan;
import simpledb.multibuffer.HashJoinPlan;
import simpledb.multibuffer.MultibufferProductPlan;
import simpledb.parse.Parser;
import simpledb.parse.QueryData;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.plan.Plan;
import simpledb.plan.PlanExplainer;
import simpledb.plan.Planner;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Executable checks for allowed physical joins, result bags, and eligibility. */
public class JoinPolicyIntegrationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SimpleDB db = new SimpleDB(Files.createTempDirectory("simpledb-policy-")
                .resolve("database").toString());
        Transaction tx = db.newTx();
        try {
            createFixture(db.planner(), tx);
            testProfiles(db, tx);
            testUnavailableProfiles(db, tx);
            testBufferEligibility(db, tx);
            testIndexSelectionTracing(db, tx);
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
        testMultiTableContinuation();
        System.out.println("All " + checks + " join-policy integration checks passed.");
    }

    private static void testMultiTableContinuation() throws Exception {
        String directory = Files.createTempDirectory("simpledb-policy-continuation-")
                .resolve("database").toString();
        SimpleDB seed = new SimpleDB(directory);
        Transaction setup = seed.newTx();
        try {
            for (String sql : Arrays.asList(
                    "create table polstart(aid int, akey int)",
                    "create table polblocked(bid int, bkey int, bound int)",
                    "create table polbridge(cid int, ckey int)",
                    "create table polfree(fid int)",
                    "create index pol_bkey on polblocked(bkey) using hash",
                    "create index pol_ckey on polbridge(ckey) using btree",
                    "insert into polstart(aid, akey) values (1, 2)",
                    "insert into polblocked(bid, bkey, bound) values (10, 2, 3)",
                    "insert into polblocked(bid, bkey, bound) values (11, 2, 1)",
                    "insert into polblocked(bid, bkey, bound) values (12, 9, 9)",
                    "insert into polbridge(cid, ckey) values (20, 2)",
                    "insert into polbridge(cid, ckey) values (21, 2)",
                    "insert into polfree(fid) values (99)",
                    "insert into polfree(fid) values (100)")) {
                seed.planner().executeUpdate(sql, setup);
            }
            setup.commit();
        } catch (RuntimeException | Error e) {
            setup.rollback();
            throw e;
        }

        // Reopening refreshes statistics: A has 1 row, B has 3, C has 2.
        // A is therefore the first relation regardless of cached insert stats.
        SimpleDB db = new SimpleDB(directory);
        Transaction tx = db.newTx();
        String query = "select aid, bid, cid from polstart, polblocked, polbridge "
                + "where aid < bound and akey = ckey and ckey = bkey";
        try {
            for (JoinMethod method : Arrays.asList(JoinMethod.INDEX, JoinMethod.HASH, JoinMethod.MERGE)) {
                JoinPolicy policy = JoinPolicy.only(method);
                QueryData data = new Parser(query).query();
                Plan start = new TablePlanner("polstart", data.pred(), tx, db.mdMgr(), policy)
                        .makeSelectPlan();
                TablePlanner blocked = new TablePlanner("polblocked", data.pred(), tx, db.mdMgr(), policy);
                check(blocked.hasJoinPredicate(start) && blocked.makeJoinPlan(start) == null,
                        "B must be connected but initially unusable under " + method);

                Plan result = plan(db, tx, policy, query);
                assertJoinCounts(result, method, 2);
                check(hasJoinWithoutField(result, planClass(method), "bid"),
                        "Planner must skip B, join A to C, then revisit B");
                assertRows(result, Arrays.asList("aid", "bid", "cid"), "1:10:20", "1:10:21");

                // Removing C = B leaves B ineligible even after A joins C.
                // Adding an unrelated relation must not allow a product bypass.
                String impossible = "select aid, bid, cid, fid "
                        + "from polstart, polblocked, polbridge, polfree "
                        + "where aid < bound and akey = ckey";
                Planner reusable = new Planner(new HeuristicQueryPlanner(db.mdMgr(), policy),
                        new IndexUpdatePlanner(db.mdMgr()));
                try {
                    reusable.createQueryPlan(impossible, tx);
                    throw new AssertionError("Expected no compliant continuation under " + method);
                } catch (IllegalStateException expected) {
                    String message = expected.getMessage();
                    check(message.contains("allowed join methods: " + policy.allowedMethods()),
                            "Failure must identify allowed methods");
                    check(message.contains("polblocked") && message.contains("aid<bound"),
                            "Failure must identify the blocked relation and connecting predicate");
                    check(message.contains("current fields:") && message.contains("cid"),
                            "Failure must describe the partial plan after joining C");
                }
                // A failed planning attempt must not poison the next query.
                Plan recovered = reusable.createQueryPlan(query, tx);
                assertRows(recovered, Arrays.asList("aid", "bid", "cid"), "1:10:20", "1:10:21");

                Plan product = plan(db, tx, policy,
                        "select aid, fid from polstart, polfree");
                assertJoinCounts(product, method, 0);
                check(countNodes(product, MultibufferProductPlan.class) == 1,
                        "A true disconnected product must remain supported under " + method);
                assertRows(product, Arrays.asList("aid", "fid"), "1:99", "1:100");

                Plan mixed = plan(db, tx, policy,
                        "select aid, bid, cid, fid from polstart, polblocked, polbridge, polfree "
                        + "where aid < bound and akey = ckey and ckey = bkey");
                assertJoinCounts(mixed, method, 2);
                check(countNodes(mixed, MultibufferProductPlan.class) == 1,
                        "A disconnected component must combine with the compliant join chain");
                assertRows(mixed, Arrays.asList("aid", "bid", "cid", "fid"),
                        "1:10:20:99", "1:10:20:100", "1:10:21:99", "1:10:21:100");
            }
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
    }

    private static boolean hasJoinWithoutField(Plan plan, Class<?> type, String field) {
        if (type.isInstance(plan) && !plan.schema().hasField(field))
            return true;
        for (Plan child : plan.children())
            if (hasJoinWithoutField(child, type, field))
                return true;
        return false;
    }

    private static void assertJoinCounts(Plan plan, JoinMethod method, int expected) {
        for (JoinMethod candidate : JoinMethod.values())
            check(countNodes(plan, planClass(candidate)) == (candidate == method ? expected : 0),
                    "Unexpected physical joins under " + method + ":\n" + PlanExplainer.explain(plan));
    }

    private static void createFixture(Planner planner, Transaction tx) {
        for (String sql : Arrays.asList(
                "create table policyleft(lid int, lkey int, lgroup int)",
                "create table policyright(rid int, rkey int, rgroup int)",
                "create index policy_lkey on policyleft(lkey) using hash",
                "create index policy_rkey on policyright(rkey) using btree",
                "insert into policyleft(lid, lkey, lgroup) values (1, 1, 1)",
                "insert into policyleft(lid, lkey, lgroup) values (2, 2, 1)",
                "insert into policyleft(lid, lkey, lgroup) values (3, 2, 2)",
                "insert into policyleft(lid, lkey, lgroup) values (4, 4, 2)",
                "insert into policyright(rid, rkey, rgroup) values (10, 2, 1)",
                "insert into policyright(rid, rkey, rgroup) values (11, 2, 2)",
                "insert into policyright(rid, rkey, rgroup) values (12, 3, 2)",
                "insert into policyright(rid, rkey, rgroup) values (13, 5, 3)",
                "create table unrelated(uid int)",
                "insert into unrelated(uid) values (99)",
                "create table unindexed(nid int, nkey int)",
                "insert into unindexed(nid, nkey) values (20, 2)",
                "create table policylstr(lsid int, lskey varchar(10))",
                "create table policyrstr(rsid int, rskey varchar(10))",
                "create index policy_lskey on policylstr(lskey) using hash",
                "create index policy_rskey on policyrstr(rskey) using btree",
                "insert into policylstr(lsid, lskey) values (1, 'blue')",
                "insert into policylstr(lsid, lskey) values (2, 'blue')",
                "insert into policyrstr(rsid, rskey) values (10, 'blue')",
                "insert into policyrstr(rsid, rskey) values (11, 'blue')")) {
            planner.executeUpdate(sql, tx);
        }
    }

    private static void testProfiles(SimpleDB db, Transaction tx) {
        for (JoinMethod method : JoinMethod.values()) {
            JoinPolicy policy = JoinPolicy.only(method);
            assertQuery(db, tx, policy, method,
                    "select lid, rid from policyleft, policyright where lkey = rkey",
                    "2:10", "2:11", "3:10", "3:11");
            // Identical projected rows must retain their multiplicity.
            assertQuery(db, tx, policy, method,
                    "select lkey, rkey from policyleft, policyright where lkey = rkey",
                    "2:2", "2:2", "2:2", "2:2");
            assertQuery(db, tx, policy, method,
                    "select lid, rid from policyleft, policyright "
                    + "where rkey = lkey and lid > 1 and rid < 11",
                    "2:10", "3:10");
            assertQuery(db, tx, policy, method,
                    "select lid, rid from policyleft, policyright "
                    + "where lkey = rkey and lgroup = rgroup",
                    "2:10", "3:11");
            assertQuery(db, tx, policy, method,
                    "select lid, rid from policyleft, policyright "
                    + "where lkey = rkey and lgroup < rgroup", "2:11");
            assertQuery(db, tx, policy, method,
                    "select lsid, rsid from policylstr, policyrstr where lskey = rskey",
                    "1:10", "1:11", "2:10", "2:11");
        }
        assertQuery(db, tx, JoinPolicy.defaultPolicy(), JoinMethod.INDEX,
                "select lid, rid from policyleft, policyright where lkey = rkey",
                "2:10", "2:11", "3:10", "3:11");
        JoinPolicy mixed = new JoinPolicy(EnumSet.of(JoinMethod.INDEX, JoinMethod.NESTED_LOOP));
        assertQuery(db, tx, mixed, JoinMethod.NESTED_LOOP,
                "select lid, rid from policyleft, policyright where lid < rgroup",
                "1:11", "1:12", "1:13", "2:13");
    }

    private static void testUnavailableProfiles(SimpleDB db, Transaction tx) {
        for (JoinMethod method : Arrays.asList(JoinMethod.INDEX, JoinMethod.HASH, JoinMethod.MERGE)) {
            assertUnavailable(db, tx, JoinPolicy.only(method),
                    "select lid, rid from policyleft, policyright where lkey < rkey");
        }
        assertUnavailable(db, tx, JoinPolicy.only(JoinMethod.INDEX),
                "select nid, uid from unindexed, unrelated where nkey = uid");
        // A genuinely disconnected product is still supported under a strict policy.
        Plan product = plan(db, tx, JoinPolicy.only(JoinMethod.HASH),
                "select lid, uid from policyleft, unrelated");
        check(countNodes(product, MultibufferProductPlan.class) == 1,
                "Disconnected inputs must be labelled as a product");
        assertRows(product, Arrays.asList("lid", "uid"), "1:99", "2:99", "3:99", "4:99");
    }

    private static void testBufferEligibility(SimpleDB db, Transaction tx) {
        QueryData data = new Parser(
                "select lid, rid from policyleft, policyright where lkey = rkey").query();
        TablePlanner outer = new TablePlanner("policyleft", data.pred(), tx, db.mdMgr());
        TablePlanner hash = new TablePlanner("policyright", data.pred(), tx, db.mdMgr(),
                JoinPolicy.only(JoinMethod.HASH));
        TablePlanner fallback = new TablePlanner("policyright", data.pred(), tx, db.mdMgr(),
                new JoinPolicy(EnumSet.of(JoinMethod.HASH, JoinMethod.MERGE)));
        Plan current = outer.makeSelectPlan();
        List<BlockId> held = new ArrayList<BlockId>();
        try {
            while (tx.availableBuffs() > 2) {
                BlockId block = tx.append("policy-held");
                tx.pin(block);
                held.add(block);
            }
            check(hash.makeJoinPlan(current) == null,
                    "Hash must remain ineligible with fewer than three available buffers");
            check(countNodes(fallback.makeJoinPlan(current), MergeJoinPlan.class) == 1,
                    "An allowed merge join must remain the low-buffer equality fallback");
        } finally {
            for (BlockId block : held)
                tx.unpin(block);
        }
    }

    private static void testIndexSelectionTracing(SimpleDB db, Transaction tx) {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(output);
        Plan selected;
        try {
            System.setOut(capture);
            selected = plan(db, tx, JoinPolicy.only(JoinMethod.HASH),
                    "select lid from policyleft where lkey = 2");
        } finally {
            System.setOut(original);
            capture.close();
        }
        check(countNodes(selected, IndexSelectPlan.class) == 1,
                "Disabling index joins must not disable index selections");
        if (Boolean.getBoolean("simpledb.traceJoins"))
            check(output.toString().contains("[planner] index on lkey used"),
                    "Index-selection tracing must use the existing opt-in trace");
        else
            check(output.size() == 0, "Index selection must be silent by default");
        assertRows(selected, Collections.singletonList("lid"), "2", "3");
    }

    private static Plan plan(SimpleDB db, Transaction tx, JoinPolicy policy, String sql) {
        return new Planner(new HeuristicQueryPlanner(db.mdMgr(), policy),
                new IndexUpdatePlanner(db.mdMgr())).createQueryPlan(sql, tx);
    }

    private static void assertQuery(SimpleDB db, Transaction tx, JoinPolicy policy,
                                    JoinMethod method, String sql, String... expected) {
        Plan result = plan(db, tx, policy, sql);
        for (JoinMethod candidate : JoinMethod.values()) {
            check(countNodes(result, planClass(candidate)) == (candidate == method ? 1 : 0),
                    "Unexpected join under " + policy.allowedMethods() + ":\n"
                    + PlanExplainer.explain(result));
        }
        check(countNodes(result, MultibufferProductPlan.class) == 0,
                "A connected two-table query must not use a product");
        assertRows(result, new Parser(sql).query().fields(), expected);
    }

    private static Class<?> planClass(JoinMethod method) {
        switch (method) {
            case INDEX: return IndexJoinPlan.class;
            case HASH: return HashJoinPlan.class;
            case MERGE: return MergeJoinPlan.class;
            case NESTED_LOOP: return NestedLoopJoinPlan.class;
            default: throw new AssertionError(method);
        }
    }

    private static int countNodes(Plan plan, Class<?> type) {
        int count = type.isInstance(plan) ? 1 : 0;
        for (Plan child : plan.children())
            count += countNodes(child, type);
        return count;
    }

    private static void assertRows(Plan plan, List<String> fields, String... expected) {
        List<String> rows = new ArrayList<String>();
        Scan scan = plan.open();
        try {
            while (scan.next()) {
                List<String> values = new ArrayList<String>();
                for (String field : fields)
                    values.add(scan.getVal(field).toString());
                rows.add(String.join(":", values));
            }
        } finally {
            scan.close();
        }
        List<String> answer = new ArrayList<String>(Arrays.asList(expected));
        Collections.sort(rows);
        Collections.sort(answer);
        check(rows.equals(answer), "Expected bag " + answer + ", got " + rows);
    }

    private static void assertUnavailable(SimpleDB db, Transaction tx, JoinPolicy policy, String sql) {
        try {
            plan(db, tx, policy, sql);
            throw new AssertionError("Expected unavailable configuration: " + sql);
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("allowed join methods: " + policy.allowedMethods()),
                    "An unavailable configuration must identify its allowed methods");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}
