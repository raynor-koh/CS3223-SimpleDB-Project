package simpledb.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import simpledb.index.planner.IndexUpdatePlanner;
import simpledb.opt.HeuristicQueryPlanner;
import simpledb.parse.Parser;
import simpledb.plan.AggregateQueryPlannerSupport;
import simpledb.plan.BasicQueryPlanner;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.query.Scan;
import simpledb.record.Schema;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Planning-time grouping validation and explicit integer/empty aggregate semantics. */
public class AggregateSemanticsIntegrationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testValidationBeforeExecution();
        Path directory = Files.createTempDirectory("simpledb-aggregate-semantics-").resolve("database");
        SimpleDB db = new SimpleDB(directory.toString());
        Transaction tx = db.newTx();
        try {
            createFixture(db.planner(), tx);
            for (Planner planner : Arrays.asList(
                    new Planner(new HeuristicQueryPlanner(db.mdMgr()), new IndexUpdatePlanner(db.mdMgr())),
                    new Planner(new BasicQueryPlanner(db.mdMgr()), new IndexUpdatePlanner(db.mdMgr())))) {
                testPlannerValidation(planner, tx, directory);
                testIntegerAverage(planner, tx);
                testEmptyAggregates(planner, tx);
            }
            check(tx.availableBuffs() == SimpleDB.BUFFER_SIZE, "No pins may remain before commit");
            tx.commit();
        } catch (RuntimeException | Error e) {
            tx.rollback();
            throw e;
        }
        System.out.println("All " + checks + " aggregate-semantics integration checks passed.");
    }

    private static List<String> invalidGroupingQueries() {
        return Arrays.asList(
                "select title from avgcases group by grp",
                "select grp, title from avgcases group by grp",
                "select distinct title from avgcases group by grp",
                "select score from avgcases group by grp, title",
                "select title, count(*) from avgcases group by grp",
                "select title, count(*) from avgcases");
    }

    private static void testValidationBeforeExecution() {
        Schema schema = new Schema();
        schema.addIntField("grp");
        schema.addIntField("score");
        schema.addStringField("title", 12);
        Plan unopened = new Plan() {
            public Schema schema() { return schema; }
            public Scan open() { throw new AssertionError("Invalid grouping opened an execution scan"); }
            public int blocksAccessed() { throw new AssertionError("Validation evaluated execution costs"); }
            public int recordsOutput() { throw new AssertionError("Validation evaluated cardinality"); }
            public int distinctValues(String field) { throw new AssertionError("Validation evaluated distinct values"); }
        };
        for (String sql : invalidGroupingQueries())
            expectRejected(() -> AggregateQueryPlannerSupport.apply(null, unopened, new Parser(sql).query()), sql);
    }

    private static void createFixture(Planner planner, Transaction tx) {
        planner.executeUpdate("create table avgcases(grp int, score int, title varchar(12))", tx);
        planner.executeUpdate("create table emptyavg(grp int, score int, title varchar(12))", tx);
        int[][] values = {
            {1, -5}, {1, 2}, {2, 5}, {2, -2}, {3, -1}, {3, 0}, {4, 1}, {4, 0},
            {5, Integer.MAX_VALUE}, {5, Integer.MAX_VALUE},
            {6, Integer.MIN_VALUE}, {6, Integer.MIN_VALUE + 1}
        };
        for (int[] row : values)
            planner.executeUpdate("insert into avgcases(grp, score, title) values ("
                    + row[0] + ", " + row[1] + ", 'g" + row[0] + "')", tx);
    }

    private static void testPlannerValidation(Planner planner, Transaction tx, Path directory) throws Exception {
        for (String sql : invalidGroupingQueries()) {
            int before = tx.availableBuffs();
            Set<String> files = temporaryFiles(directory);
            expectRejected(() -> planner.createQueryPlan(sql, tx), sql);
            expectRejected(() -> planner.explain("explain " + sql, tx), "EXPLAIN " + sql);
            check(tx.availableBuffs() == before, "Invalid query leaked buffers: " + sql);
            check(temporaryFiles(directory).equals(files), "Invalid query created execution temp tables: " + sql);
        }
        verify(planner, tx, "select grp from avgcases group by grp order by grp",
                rows(new Object[] {1}, new Object[] {2}, new Object[] {3}, new Object[] {4},
                     new Object[] {5}, new Object[] {6}));
        verify(planner, tx, "select title from avgcases group by grp, title order by title",
                rows(new Object[] {"g1"}, new Object[] {"g2"}, new Object[] {"g3"},
                     new Object[] {"g4"}, new Object[] {"g5"}, new Object[] {"g6"}));
        verify(planner, tx, "select score from avgcases where grp = 1 order by score",
                rows(new Object[] {-5}, new Object[] {2}));
    }

    private static void testIntegerAverage(Planner planner, Transaction tx) {
        // Negative fractional averages truncate toward zero, not toward -infinity.
        // Large same-sign pairs also require an accumulator wider than int.
        verify(planner, tx, "select grp, avg(score), count(*) from avgcases group by grp order by grp",
                rows(new Object[] {1, -1, 2}, new Object[] {2, 1, 2}, new Object[] {3, 0, 2},
                     new Object[] {4, 0, 2}, new Object[] {5, Integer.MAX_VALUE, 2},
                     new Object[] {6, Integer.MIN_VALUE + 1, 2}));
        verify(planner, tx, "select avg(score), count(*) from avgcases", rows(new Object[] {0, 12}));
        verify(planner, tx, "select avg(score) from avgcases where grp = 1", rows(new Object[] {-1}));
        verify(planner, tx, "select avg(score) from avgcases where grp = 2", rows(new Object[] {1}));
        Plan plan = planner.createQueryPlan("select avg(score) from avgcases", tx);
        check(plan.schema().type("avgofscore") == Types.INTEGER, "AVG must retain integer output type");
    }

    private static void testEmptyAggregates(Planner planner, Transaction tx) {
        String all = "count(*), sum(score), avg(score), min(score), max(score)";
        for (String input : Arrays.asList(" from emptyavg", " from avgcases where grp = 999")) {
            for (String projection : Arrays.asList("count(*)", "count(score)", "sum(score)",
                    "avg(score)", "min(score)", "max(score)", all,
                    "count(*), min(title), max(title)"))
                verify(planner, tx, "select " + projection + input, Collections.emptyList());
            verify(planner, tx, "select grp, " + all + input + " group by grp", Collections.emptyList());
            verify(planner, tx, "select grp" + input + " group by grp", Collections.emptyList());
        }
    }

    private static void verify(Planner planner, Transaction tx, String sql, List<List<Object>> expected) {
        int before = tx.availableBuffs();
        Plan plan = planner.createQueryPlan(sql, tx);
        List<List<Object>> actual = QueryResultAssertions.readRows(plan, new Parser(sql).query().fields());
        QueryResultAssertions.assertBagEquals(sql, QueryResultAssertions.bag(expected), QueryResultAssertions.bag(actual));
        checks++;
        if (new Parser(sql).query().hasOrderBy()) {
            QueryResultAssertions.assertSequenceEquals(sql, expected, actual);
            checks++;
        }
        check(tx.availableBuffs() == before, "Query leaked buffers before commit: " + sql);
    }

    private static void expectRejected(Runnable action, String context) {
        try {
            action.run();
            throw new AssertionError("Expected planning-time rejection: " + context);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("must appear in GROUP BY"),
                    "Grouping diagnostic must explain the ungrouped selected field: " + context);
        }
    }

    private static Set<String> temporaryFiles(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("temp[0-9]+\\.tbl")).collect(Collectors.toSet());
        }
    }

    private static List<List<Object>> rows(Object[]... tuples) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] tuple : tuples)
            result.add(Arrays.asList(tuple));
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}
