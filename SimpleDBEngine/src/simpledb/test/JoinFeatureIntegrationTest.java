package simpledb.test;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import simpledb.index.planner.IndexJoinPlan;
import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.materialize.MergeJoinPlan;
import simpledb.multibuffer.HashJoinPlan;
import simpledb.multibuffer.MultibufferProductPlan;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.plan.Plan;
import simpledb.plan.PlanExplainer;
import simpledb.plan.Planner;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Cross-feature correctness checks using fixed inputs and independent answers. */
public class JoinFeatureIntegrationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String directory = Files.createTempDirectory("simpledb-join-features-")
                .resolve("database").toString();
        SimpleDB seed = new SimpleDB(directory);
        Transaction setup = seed.newTx();
        try {
            JoinFeatureFixture.create(seed.planner(), setup);
            setup.commit();
        } catch (RuntimeException | Error e) {
            setup.rollback();
            throw e;
        }
        // Reopen to obtain consistent statistics after data and index creation.
        SimpleDB db = new SimpleDB(directory);
        Transaction tx = db.newTx();
        try {
            for (JoinMethod method : JoinMethod.values())
                testProfile(db, tx, JoinPolicy.only(method), method);
            testProfile(db, tx, JoinPolicy.defaultPolicy(), JoinMethod.INDEX);
            testNonEquality(db, tx);
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
        System.out.println("All " + checks + " join-feature integration checks passed.");
    }

    private static void testProfile(SimpleDB db, Transaction tx, JoinPolicy policy, JoinMethod method) {
        // A key occurring twice in A and three times in B yields six pairs;
        // A's key 20 and B's keys 30/40 have no partner.
        verify(db, tx, policy, method, false, "aid, atag, bid, ak, bk", "",
                row -> true, 6);
        verify(db, tx, policy, method, false, "ak, bk", "", row -> true, 6);
        verify(db, tx, policy, method, false, "aid, atag, bid", " and agrp = bg",
                row -> row.get("agrp").equals(row.get("bg")), 3);
        verify(db, tx, policy, method, false, "aid, atag, bid", " and aid < blimit",
                row -> number(row, "aid") < number(row, "blimit"), 3);
        verify(db, tx, policy, method, false, "aid, bid",
                " and agrp = bg and aid < blimit and bid < 104",
                row -> row.get("agrp").equals(row.get("bg"))
                    && number(row, "aid") < number(row, "blimit")
                    && number(row, "bid") < 104, 1);
        verify(db, tx, policy, method, false, "aid, bid", " and ak = 20",
                row -> number(row, "ak") == 20, 0);

        verify(db, tx, policy, method, true, "aid, atag, bid, cid, did, dtag", "",
                row -> true, 14);
        // Dropping identity fields must preserve multiplicity, including the
        // two D records projecting to the same string tag.
        verify(db, tx, policy, method, true, "atag, dtag", "", row -> true, 14);
        verify(db, tx, policy, method, true, "aid, atag, bid, cid, did, dtag",
                " and agrp = bg and aid < blimit and cscore = dscore",
                row -> row.get("agrp").equals(row.get("bg"))
                    && number(row, "aid") < number(row, "blimit")
                    && row.get("cscore").equals(row.get("dscore")), 2);
        verify(db, tx, policy, method, true, "aid, bid, cid, did",
                " and aid < dscore and bid < 104",
                row -> number(row, "aid") < number(row, "dscore")
                    && number(row, "bid") < 104, 4);
    }

    private static void verify(SimpleDB db, Transaction tx, JoinPolicy policy, JoinMethod method,
            boolean four, String projection, String residual,
            Predicate<Map<String, Object>> filter, int expectedCount) {
        List<String> fields = Arrays.asList(projection.split(", "));
        List<List<Object>> expected = JoinFeatureFixture.expected(four, true, filter, fields);
        check(expected.size() == expectedCount, "Reference fixture count changed for " + projection + residual);
        String sql = "select " + projection + " from "
                + (four ? JoinFeatureFixture.FOUR_FROM : JoinFeatureFixture.TWO_FROM) + " where "
                + (four ? JoinFeatureFixture.FOUR_JOIN : JoinFeatureFixture.TWO_JOIN) + residual;
        Plan plan = planner(db, policy).createQueryPlan(sql, tx);
        assertOperators(plan, method, four ? 3 : 1);
        QueryResultAssertions.assertBagEquals(policy.allowedMethods() + ": " + sql,
                QueryResultAssertions.bag(expected), QueryResultAssertions.readBag(plan, fields));
        checks++;
    }

    private static void testNonEquality(SimpleDB db, Transaction tx) {
        List<String> fields = Arrays.asList("aid", "atag", "bid");
        List<List<Object>> expected = JoinFeatureFixture.expected(false, false,
                row -> number(row, "aid") < number(row, "bg"), fields);
        check(expected.size() == 3, "Reference non-equality count changed");
        Plan plan = planner(db, JoinPolicy.only(JoinMethod.NESTED_LOOP)).createQueryPlan(
                "select aid, atag, bid from l7a, l7b where aid < bg", tx);
        assertOperators(plan, JoinMethod.NESTED_LOOP, 1);
        QueryResultAssertions.assertBagEquals("Pure non-equality join",
                QueryResultAssertions.bag(expected), QueryResultAssertions.readBag(plan, fields));
        checks++;
    }

    private static int number(Map<String, Object> row, String field) {
        return (Integer) row.get(field);
    }

    private static Planner planner(SimpleDB db, JoinPolicy policy) {
        return new Planner(new HeuristicQueryPlanner(db.mdMgr(), policy),
                new IndexUpdatePlanner(db.mdMgr()));
    }

    private static void assertOperators(Plan plan, JoinMethod method, int count) {
        for (JoinMethod candidate : JoinMethod.values()) {
            Class<?> type;
            switch (candidate) {
                case INDEX: type = IndexJoinPlan.class; break;
                case HASH: type = HashJoinPlan.class; break;
                case MERGE: type = MergeJoinPlan.class; break;
                case NESTED_LOOP: type = NestedLoopJoinPlan.class; break;
                default: throw new AssertionError(candidate);
            }
            check(countNodes(plan, type) == (candidate == method ? count : 0),
                    "Unexpected joins for " + method + ":\n" + PlanExplainer.explain(plan));
        }
        check(countNodes(plan, MultibufferProductPlan.class) == 0,
                "Connected fixture query must not contain products");
    }

    private static int countNodes(Plan plan, Class<?> type) {
        int count = type.isInstance(plan) ? 1 : 0;
        for (Plan child : plan.children())
            count += countNodes(child, type);
        return count;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}
