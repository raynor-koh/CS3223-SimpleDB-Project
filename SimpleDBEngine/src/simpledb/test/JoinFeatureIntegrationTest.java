package simpledb.test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import simpledb.index.planner.IndexJoinPlan;
import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.materialize.MergeJoinPlan;
import simpledb.materialize.DistinctPlan;
import simpledb.materialize.GroupByPlan;
import simpledb.materialize.SortPlan;
import simpledb.multibuffer.HashJoinPlan;
import simpledb.multibuffer.MultibufferProductPlan;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.parse.Parser;
import simpledb.parse.QueryData;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.plan.Plan;
import simpledb.plan.PlanExplainer;
import simpledb.plan.Planner;
import simpledb.plan.ProjectPlan;
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
        testFinalStages(db, tx, policy, method);
    }

    private static void testFinalStages(SimpleDB db, Transaction tx, JoinPolicy policy, JoinMethod method) {
        String from = " from l7a, l7b where ak = bk";
        String aggregates = "count(*), sum(blimit), avg(blimit), min(blimit), max(blimit)";
        // Both A groups join the same three B values [4, 2, 1]. Preserve
        // multiplicity: global count/sum are 6/14, not 3/7.
        verifyFinalQuery(db, tx, policy, method, "select " + aggregates + from, 1,
                rows(new Object[] {6, 14, 2, 1, 4}));
        verifyFinalQuery(db, tx, policy, method,
                "select agrp, " + aggregates + from + " group by agrp", 1,
                rows(new Object[] {1, 3, 7, 2, 1, 4}, new Object[] {2, 3, 7, 2, 1, 4}));
        // Dropping the grouping key leaves two identical complete tuples.
        // DISTINCT must collapse those final tuples, not the aggregate inputs.
        verifyFinalQuery(db, tx, policy, method,
                "select " + aggregates + from + " group by agrp", 1,
                rows(new Object[] {3, 7, 2, 1, 4}, new Object[] {3, 7, 2, 1, 4}));
        verifyFinalQuery(db, tx, policy, method,
                "select distinct " + aggregates + from + " group by agrp", 1,
                rows(new Object[] {3, 7, 2, 1, 4}));

        // Every primary sort key has ties, so the secondary direction matters.
        String grouped = "select aid, bg, " + aggregates + from + " group by aid, bg";
        verifyFinalQuery(db, tx, policy, method, grouped + " order by aid asc, bg desc", 1,
                rows(new Object[] {1, 2, 1, 2, 2, 2, 2}, new Object[] {1, 1, 2, 5, 2, 1, 4},
                     new Object[] {2, 2, 1, 2, 2, 2, 2}, new Object[] {2, 1, 2, 5, 2, 1, 4}));
        verifyFinalQuery(db, tx, policy, method, grouped + " order by aid desc, bg asc", 1,
                rows(new Object[] {2, 1, 2, 5, 2, 1, 4}, new Object[] {2, 2, 1, 2, 2, 2, 2},
                     new Object[] {1, 1, 2, 5, 2, 1, 4}, new Object[] {1, 2, 1, 2, 2, 2, 2}));
        verifyFinalQuery(db, tx, policy, method, grouped
                + " order by countofall desc, aid asc, bg desc", 1,
                rows(new Object[] {1, 1, 2, 5, 2, 1, 4}, new Object[] {2, 1, 2, 5, 2, 1, 4},
                     new Object[] {1, 2, 1, 2, 2, 2, 2}, new Object[] {2, 2, 1, 2, 2, 2, 2}));

        verifyFinalQuery(db, tx, policy, method,
                "select bg, " + aggregates + from + " group by aid, bg", 1,
                rows(new Object[] {1, 2, 5, 2, 1, 4}, new Object[] {1, 2, 5, 2, 1, 4},
                     new Object[] {2, 1, 2, 2, 2, 2}, new Object[] {2, 1, 2, 2, 2, 2}));
        verifyFinalQuery(db, tx, policy, method,
                "select distinct bg, " + aggregates + from
                + " group by aid, bg order by countofall desc, bg asc", 1,
                rows(new Object[] {1, 2, 5, 2, 1, 4}, new Object[] {2, 1, 2, 2, 2, 2}));

        verifyFinalQuery(db, tx, policy, method,
                "select distinct bg, agrp" + from + " order by bg desc, agrp asc", 1,
                rows(new Object[] {2, 1}, new Object[] {2, 2}, new Object[] {1, 1}, new Object[] {1, 2}));
        verifyFinalQuery(db, tx, policy, method,
                "select aid, blimit" + from + " order by aid asc, blimit desc", 1,
                rows(new Object[] {1, 4}, new Object[] {1, 2}, new Object[] {1, 1},
                     new Object[] {2, 4}, new Object[] {2, 2}, new Object[] {2, 1}));

        String fourFrom = " from " + JoinFeatureFixture.FOUR_FROM
                + " where " + JoinFeatureFixture.FOUR_JOIN;
        String fourAggregates = "count(*), sum(dscore), avg(dscore), min(dscore), max(dscore)";
        verifyFinalQuery(db, tx, policy, method, "select " + fourAggregates + fourFrom, 3,
                rows(new Object[] {14, 24, 1, 1, 2}));
        verifyFinalQuery(db, tx, policy, method,
                "select distinct " + fourAggregates + fourFrom + " group by agrp", 3,
                rows(new Object[] {7, 12, 1, 1, 2}));
    }

    private static void verifyFinalQuery(SimpleDB db, Transaction tx, JoinPolicy policy,
            JoinMethod method, String sql, int joinCount, List<List<Object>> expected) {
        QueryData data = new Parser(sql).query();
        Plan plan = planner(db, policy).createQueryPlan(sql, tx);
        assertOperators(plan, method, joinCount);
        // Verify the physical final-stage order as well as the actual answers.
        Plan stage = plan;
        if (data.hasOrderBy()) {
            check(stage instanceof SortPlan, "ORDER BY must be the final stage: " + sql);
            stage = stage.children().get(0);
        }
        if (data.isDistinct()) {
            check(stage instanceof DistinctPlan, "DISTINCT must follow projection: " + sql);
            // Distinct exposes its sorting child, then that child's input.
            stage = stage.children().get(0).children().get(0);
        }
        check(stage instanceof ProjectPlan, "Final projection must precede DISTINCT/order: " + sql);
        if (data.hasAggregates())
            check(stage.children().get(0) instanceof GroupByPlan,
                    "Grouping/aggregation must precede final projection: " + sql);
        List<List<Object>> actual = QueryResultAssertions.readRows(plan, data.fields());
        QueryResultAssertions.assertBagEquals(policy.allowedMethods() + ": " + sql,
                QueryResultAssertions.bag(expected), QueryResultAssertions.bag(actual));
        checks++;
        if (data.hasOrderBy()) {
            QueryResultAssertions.assertSequenceEquals(policy.allowedMethods() + ": " + sql, expected, actual);
            checks++;
        }
    }

    private static List<List<Object>> rows(Object[]... tuples) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] tuple : tuples)
            rows.add(Arrays.asList(tuple));
        return rows;
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
