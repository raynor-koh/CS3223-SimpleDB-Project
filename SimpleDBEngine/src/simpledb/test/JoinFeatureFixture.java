package simpledb.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import simpledb.plan.Planner;
import simpledb.tx.Transaction;

/** Small fixed join fixture and an independent, in-memory reference calculation. */
final class JoinFeatureFixture {
    static final String TWO_FROM = "l7a, l7b";
    static final String FOUR_FROM = "l7a, l7b, l7c, l7d";
    static final String TWO_JOIN = "ak = bk";
    static final String FOUR_JOIN = "ak = bk and bnext = ck and cnext = dk";

    private static final String[][] FIELDS = {
        {"aid", "ak", "agrp", "atag"},
        {"bid", "bk", "bg", "bnext", "blimit"},
        {"cid", "ck", "cnext", "cscore"},
        {"did", "dk", "dscore", "dtag"}
    };
    private static final Object[][][] ROWS = {
        {{1, 10, 1, "amy"}, {2, 10, 2, "bob"}, {3, 20, 1, "cara"}},
        {{101, 10, 1, 100, 4}, {102, 10, 2, 100, 2}, {103, 30, 1, 200, 4},
         {104, 10, 1, 300, 1}, {105, 40, 3, 400, 8}},
        {{201, 100, 1000, 1}, {202, 100, 2000, 2}, {203, 200, 1000, 1},
         {204, 300, 3000, 2}, {205, 900, 9000, 0}, {206, 400, 4000, 3}},
        {{301, 1000, 1, "x"}, {302, 1000, 2, "x"}, {303, 2000, 2, "y"},
         {304, 3000, 2, "z"}, {305, 9000, 0, "unused"},
         {306, 4000, 3, "other"}, {307, 8000, 4, "unmatched"}}
    };

    private JoinFeatureFixture() { }

    static void create(Planner planner, Transaction tx) {
        String[] definitions = {
            "aid int, ak int, agrp int, atag varchar(12)",
            "bid int, bk int, bg int, bnext int, blimit int",
            "cid int, ck int, cnext int, cscore int",
            "did int, dk int, dscore int, dtag varchar(12)"
        };
        for (int table = 0; table < ROWS.length; table++) {
            String name = "l7" + (char) ('a' + table);
            planner.executeUpdate("create table " + name + "(" + definitions[table] + ")", tx);
        }
        // Both ends of every edge are indexed, permitting either orientation.
        String[] indexedFields = {"ak", "bk", "bnext", "ck", "cnext", "dk"};
        String[] tables = {"l7a", "l7b", "l7b", "l7c", "l7c", "l7d"};
        for (int i = 0; i < indexedFields.length; i++) {
            String field = indexedFields[i];
            planner.executeUpdate("create index l7_" + field + " on " + tables[i]
                    + "(" + field + ") using " + (i % 2 == 0 ? "hash" : "btree"), tx);
        }
        // Create indexes before inserting: CREATE INDEX does not backfill
        // existing rows in this engine. IndexUpdatePlanner maintains each insert.
        for (int table = 0; table < ROWS.length; table++) {
            String name = "l7" + (char) ('a' + table);
            for (Object[] row : ROWS[table]) {
                String values = Arrays.stream(row).map(value -> value instanceof String
                        ? "'" + value + "'" : value.toString()).collect(Collectors.joining(", "));
                planner.executeUpdate("insert into " + name + "("
                        + String.join(", ", FIELDS[table]) + ") values (" + values + ")", tx);
            }
        }
    }

    /** Enumerates source tuples directly, without SQL, plans, or engine scans. */
    static List<List<Object>> expected(boolean fourTables, boolean equalityJoin,
            Predicate<Map<String, Object>> filter, List<String> projectedFields) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] a : ROWS[0]) {
            for (Object[] b : ROWS[1]) {
                if (equalityJoin && !a[1].equals(b[1]))
                    continue;
                if (!fourTables) {
                    collect(result, filter, projectedFields, a, b);
                    continue;
                }
                for (Object[] c : ROWS[2]) {
                    if (!b[3].equals(c[1]))
                        continue;
                    for (Object[] d : ROWS[3]) {
                        if (c[2].equals(d[1]))
                            collect(result, filter, projectedFields, a, b, c, d);
                    }
                }
            }
        }
        return result;
    }

    private static void collect(List<List<Object>> result, Predicate<Map<String, Object>> filter,
                                List<String> projectedFields, Object[]... rows) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int table = 0; table < rows.length; table++)
            for (int field = 0; field < FIELDS[table].length; field++)
                values.put(FIELDS[table][field], rows[table][field]);
        if (filter.test(values)) {
            List<Object> tuple = new ArrayList<>();
            for (String field : projectedFields)
                tuple.add(values.get(field));
            result.add(tuple);
        }
    }
}
