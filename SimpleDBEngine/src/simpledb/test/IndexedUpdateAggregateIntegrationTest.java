package simpledb.test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import simpledb.index.Index;
import simpledb.index.btree.BTreeIndex;
import simpledb.index.hash.HashIndex;
import simpledb.index.planner.IndexJoinPlan;
import simpledb.index.planner.IndexSelectPlan;
import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.metadata.IndexInfo;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.opt.JoinMethod;
import simpledb.opt.JoinPolicy;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.plan.TablePlan;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.TableScan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Maintained index entries and joined aggregates across mutations and reopen. */
public class IndexedUpdateAggregateIntegrationTest {
    private static final List<String> FACT_FIELDS = Arrays.asList("fid", "fkey", "amount");
    private static final List<String> DIM_FIELDS = Arrays.asList("did", "dkey", "dname");
    private static final List<String> AGG_FIELDS = Arrays.asList(
            "countofall", "sumofamount", "avgofamount", "minofamount", "maxofamount");
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (String type : Arrays.asList("hash", "btree"))
            testIndexType(type);
        System.out.println("All " + checks + " indexed-update aggregate integration checks passed.");
    }

    private static void testIndexType(String type) throws Exception {
        String directory = Files.createTempDirectory("simpledb-updated-aggregates-" + type + "-")
                .resolve("database").toString();
        SimpleDB db = new SimpleDB(directory);
        State expected = new State();
        Transaction setup = db.newTx();
        try {
            Planner planner = planner(db, JoinPolicy.defaultPolicy());
            for (String sql : Arrays.asList(
                    "create table facts(fid int, fkey int, amount int)",
                    "create table dimensions(did int, dkey int, dname varchar(12))",
                    "create index fact_key on facts(fkey) using " + type,
                    "create index fact_amount on facts(amount) using " + type,
                    "create index dim_key on dimensions(dkey) using " + type))
                planner.executeUpdate(sql, setup);
            for (Object[] row : expected.facts)
                check(planner.executeUpdate("insert into facts(fid, fkey, amount) values ("
                        + row[0] + ", " + row[1] + ", " + row[2] + ")", setup) == 1,
                        "Initial fact insert count");
            for (Object[] row : expected.dimensions)
                check(planner.executeUpdate("insert into dimensions(did, dkey, dname) values ("
                        + row[0] + ", " + row[1] + ", '" + row[2] + "')", setup) == 1,
                        "Initial dimension insert count");
            verify(db, setup, expected, type, "initial before commit");
            setup.commit();
        } catch (RuntimeException | Error e) {
            setup.rollback();
            throw e;
        }
        // SimpleDB has no close() method. All scans above close explicitly;
        // commit releases transaction resources, then reconstruct metadata by reopening.
        db = new SimpleDB(directory);
        verifyCommitted(db, expected, type, "initial reopened");

        int stage = 0;
        for (Mutation mutation : mutations()) {
            Transaction tx = db.newTx();
            try {
                int affected = planner(db, JoinPolicy.defaultPolicy()).executeUpdate(mutation.sql, tx);
                check(affected == mutation.affected, "Wrong update count: " + mutation.sql);
                mutation.change.accept(expected);
                verify(db, tx, expected, type, "stage " + stage + " before commit: " + mutation.sql);
                tx.commit();
            } catch (RuntimeException | Error e) {
                tx.rollback();
                throw e;
            }
            db = new SimpleDB(directory);
            verifyCommitted(db, expected, type, "stage " + stage + " reopened");
            stage++;
        }
    }

    private static List<Mutation> mutations() {
        return Arrays.asList(
            new Mutation("insert into facts(fid, fkey, amount) values (5, 10, 17)", 1,
                    state -> state.facts.add(new Object[] {5, 10, 17})),
            new Mutation("update facts set fkey = 20 where fid = 2", 1,
                    state -> state.fact(2)[1] = 20),
            new Mutation("update facts set amount = 19 where fid = 1", 1,
                    state -> state.fact(1)[2] = 19),
            new Mutation("update facts set fkey = 20 where fkey = 10", 2,
                    state -> state.facts.stream().filter(row -> row[1].equals(10))
                            .forEach(row -> row[1] = 20)),
            new Mutation("delete from facts where fkey = 20 and amount < 15", 2,
                    state -> state.facts.removeIf(row -> row[1].equals(20) && (Integer) row[2] < 15)),
            // This insert can reuse a deleted slot; stale index RIDs must not reappear.
            new Mutation("insert into facts(fid, fkey, amount) values (6, 10, 23)", 1,
                    state -> state.facts.add(new Object[] {6, 10, 23})),
            new Mutation("update dimensions set dkey = 10 where did = 3", 1,
                    state -> state.dimensions.get(2)[1] = 10),
            new Mutation("delete from facts where fkey = 99", 1,
                    state -> state.facts.removeIf(row -> row[1].equals(99))),
            new Mutation("delete from facts where fid >= 0", 3, state -> state.facts.clear()),
            new Mutation("insert into facts(fid, fkey, amount) values (7, 10, -3)", 1,
                    state -> state.facts.add(new Object[] {7, 10, -3})),
            new Mutation("delete from dimensions where dkey = 10", 3,
                    state -> state.dimensions.removeIf(row -> row[1].equals(10)))
        );
    }

    private static void verifyCommitted(SimpleDB db, State expected, String type, String stage) {
        Transaction tx = db.newTx();
        try {
            verify(db, tx, expected, type, stage);
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
    }

    private static void verify(SimpleDB db, Transaction tx, State state, String type, String stage) {
        String context = type + " " + stage;
        int before = tx.availableBuffs();
        assertBag(context + " full facts", state.facts,
                QueryResultAssertions.readBag(new TablePlan(tx, "facts", db.mdMgr()), FACT_FIELDS));
        assertBag(context + " full dimensions", state.dimensions,
                QueryResultAssertions.readBag(new TablePlan(tx, "dimensions", db.mdMgr()), DIM_FIELDS));
        verifyIndex(db, tx, type, context, "facts", "fkey", FACT_FIELDS, state.facts, 1,
                Arrays.asList(10, 20, 40, 99, 777));
        verifyIndex(db, tx, type, context, "facts", "amount", FACT_FIELDS, state.facts, 2,
                Arrays.asList(-3, 5, 7, 11, 13, 17, 19, 23, 999));
        verifyIndex(db, tx, type, context, "dimensions", "dkey", DIM_FIELDS, state.dimensions, 1,
                Arrays.asList(10, 20, 40, 99, 777));

        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        List<Integer> joined = new ArrayList<>();
        // Compute expected aggregates from source model rows, preserving the
        // m x n multiplication of duplicate dimension/fact keys.
        for (Object[] fact : state.facts)
            for (Object[] dimension : state.dimensions)
                if (fact[1].equals(dimension[1])) {
                    int amount = (Integer) fact[2];
                    joined.add(amount);
                    groups.computeIfAbsent((Integer) dimension[1], key -> new ArrayList<>()).add(amount);
                }
        List<Object[]> groupedRows = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> group : groups.entrySet()) {
            List<Object> row = new ArrayList<>();
            row.add(group.getKey());
            row.addAll(aggregates(group.getValue()));
            groupedRows.add(row.toArray());
        }
        // Preserve the engine's documented zero-row global aggregation on
        // empty input; SQL-standard empty COUNT semantics are a separate task.
        List<Object[]> globalRows = joined.isEmpty() ? Collections.emptyList()
                : Collections.singletonList(aggregates(joined).toArray());
        for (JoinPolicy policy : Arrays.asList(JoinPolicy.defaultPolicy(), JoinPolicy.only(JoinMethod.INDEX))) {
            Planner planner = planner(db, policy);
            String aggregate = "count(*), sum(amount), avg(amount), min(amount), max(amount)";
            Plan global = planner.createQueryPlan("select " + aggregate
                    + " from facts, dimensions where fkey = dkey", tx);
            check(countNodes(global, IndexJoinPlan.class) == 1, context + ": joined aggregate must use index join");
            assertBag(context + " global aggregate", globalRows,
                    QueryResultAssertions.readBag(global, AGG_FIELDS));
            List<String> fields = new ArrayList<>();
            fields.add("dkey");
            fields.addAll(AGG_FIELDS);
            Plan grouped = planner.createQueryPlan("select dkey, " + aggregate
                    + " from facts, dimensions where fkey = dkey group by dkey order by dkey", tx);
            check(countNodes(grouped, IndexJoinPlan.class) == 1, context + ": grouped query must use index join");
            assertBag(context + " grouped aggregate", groupedRows,
                    QueryResultAssertions.readBag(grouped, fields));
        }
        check(tx.availableBuffs() == before, context + ": verification leaked buffers before commit");
    }

    private static void verifyIndex(SimpleDB db, Transaction tx, String type, String context,
            String table, String key, List<String> fields, List<Object[]> rows, int keyPosition,
            List<Integer> probes) {
        IndexInfo info = db.mdMgr().getIndexInfo(table, tx).get(key);
        check(info != null && info.indexType().equals(type), context + ": index type " + table + "." + key);
        Index index = info.open();
        TableScan scan = (TableScan) new TablePlan(tx, table, db.mdMgr()).open();
        try {
            check(type.equals("hash") ? index instanceof HashIndex : index instanceof BTreeIndex,
                    context + ": actual backing index type");
            for (int probe : probes) {
                List<Object[]> expected = new ArrayList<>();
                for (Object[] row : rows)
                    if (row[keyPosition].equals(probe))
                        expected.add(row);
                List<List<Object>> actual = new ArrayList<>();
                index.beforeFirst(new Constant(probe));
                while (index.next()) {
                    scan.moveToRid(index.getDataRid());
                    List<Object> tuple = new ArrayList<>();
                    for (String field : fields)
                        tuple.add(field.equals("dname") ? scan.getString(field) : scan.getInt(field));
                    actual.add(tuple);
                }
                assertBag(context + " raw index " + table + "." + key + "=" + probe,
                        expected, QueryResultAssertions.bag(actual));
                String sql = "select " + String.join(", ", fields) + " from " + table + " where " + key + " = " + probe;
                Plan selected = planner(db, JoinPolicy.defaultPolicy()).createQueryPlan(sql, tx);
                check(countNodes(selected, IndexSelectPlan.class) == 1, context + ": lookup must use index selection");
                assertBag(context + " SQL index lookup " + sql, expected,
                        QueryResultAssertions.readBag(selected, fields));
            }
        } finally {
            scan.close();
            index.close();
        }
    }

    private static List<Object> aggregates(List<Integer> amounts) {
        int sum = amounts.stream().mapToInt(Integer::intValue).sum();
        return Arrays.asList(amounts.size(), sum, sum / amounts.size(),
                Collections.min(amounts), Collections.max(amounts));
    }

    private static void assertBag(String context, List<Object[]> rows, Map<List<Object>, Integer> actual) {
        List<List<Object>> tuples = new ArrayList<>();
        for (Object[] row : rows)
            tuples.add(Arrays.asList(row));
        QueryResultAssertions.assertBagEquals(context, QueryResultAssertions.bag(tuples), actual);
        checks++;
    }

    private static Planner planner(SimpleDB db, JoinPolicy policy) {
        return new Planner(new HeuristicQueryPlanner(db.mdMgr(), policy), new IndexUpdatePlanner(db.mdMgr()));
    }

    private static int countNodes(Plan plan, Class<?> type) {
        int count = type.isInstance(plan) ? 1 : 0;
        for (Plan child : plan.children())
            count += countNodes(child, type);
        return count;
    }

    private static class State {
        final List<Object[]> facts = new ArrayList<>(Arrays.asList(
                new Object[] {1, 10, 5}, new Object[] {2, 10, 7},
                new Object[] {3, 20, 11}, new Object[] {4, 99, 13}));
        final List<Object[]> dimensions = new ArrayList<>(Arrays.asList(
                new Object[] {1, 10, "alpha"}, new Object[] {2, 10, "beta"},
                new Object[] {3, 20, "gamma"}, new Object[] {4, 40, "unused"}));

        Object[] fact(int id) {
            return facts.stream().filter(row -> row[0].equals(id)).findFirst().get();
        }
    }

    private static class Mutation {
        final String sql;
        final int affected;
        final Consumer<State> change;

        Mutation(String sql, int affected, Consumer<State> change) {
            this.sql = sql;
            this.affected = affected;
            this.change = change;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}
