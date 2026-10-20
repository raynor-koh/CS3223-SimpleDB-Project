package simpledb.test;

import static java.sql.Types.INTEGER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import simpledb.plan.Plan;
import simpledb.query.Constant;
import simpledb.query.Scan;

/** Helpers for comparing complete typed tuples, retaining duplicate counts. */
final class QueryResultAssertions {
    private QueryResultAssertions() { }

    static Map<List<Object>, Integer> bag(List<List<Object>> rows) {
        Map<List<Object>, Integer> result = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            List<Object> tuple = Collections.unmodifiableList(new ArrayList<>(row));
            result.merge(tuple, 1, Integer::sum);
        }
        return result;
    }

    static Map<List<Object>, Integer> readBag(Plan plan, List<String> fields) {
        return bag(readRows(plan, fields));
    }

    /** Reads the complete typed sequence; use only ORDER BY to assert its order. */
    static List<List<Object>> readRows(Plan plan, List<String> fields) {
        List<List<Object>> rows = new ArrayList<>();
        Scan scan = plan.open();
        try {
            while (scan.next()) {
                List<Object> tuple = new ArrayList<>();
                for (String field : fields) {
                    Constant value = scan.getVal(field);
                    tuple.add(plan.schema().type(field) == INTEGER
                            ? value.asInt() : value.asString());
                }
                rows.add(tuple);
            }
        } finally {
            scan.close();
        }
        return rows;
    }

    static void assertSequenceEquals(String context, List<List<Object>> expected,
                                      List<List<Object>> actual) {
        if (!expected.equals(actual))
            throw new AssertionError(context + "\nExpected tuple sequence: " + expected
                    + "\nActual tuple sequence: " + actual);
    }

    static void assertBagEquals(String context, Map<List<Object>, Integer> expected,
                                Map<List<Object>, Integer> actual) {
        if (!expected.equals(actual))
            throw new AssertionError(context + "\nExpected tuple bag: " + expected
                    + "\nActual tuple bag: " + actual);
    }
}
