package simpledb.parse;

import java.util.Arrays;
import java.util.Collections;

/**
 * Deterministic tests for parsing ORDER BY clauses.
 */
public class OrderByParserTest {
   private static int checksRun;

   public static void main(String[] args) {
      QueryData noOrder = new Parser(
            "select sid, sname from student where sid >= 10").query();
      check(!noOrder.hasOrderBy(),
            "Query without ORDER BY should not request sorting");
      check(noOrder.orderFields().equals(Collections.emptyList()),
            "Query without ORDER BY should have no sort fields");

      QueryData defaultAscending = new Parser(
            "select sid, sname from student order by sname").query();
      checkOrder(defaultAscending,
            new String[] { "sname" }, new Boolean[] { true });

      QueryData mixed = new Parser(
            "select sid, sname, gradyear from student "
            + "where gradyear >= 2020 "
            + "order by gradyear asc, sname desc").query();
      checkOrder(mixed,
            new String[] { "gradyear", "sname" },
            new Boolean[] { true, false });
      check(mixed.toString().endsWith(
            "order by gradyear asc, sname desc"),
            "QueryData should include ORDER BY in its string form");

      QueryData caseInsensitive = new Parser(
            "SELECT SID, SNAME FROM STUDENT ORDER BY SNAME DESC, SID ASC")
            .query();
      checkOrder(caseInsensitive,
            new String[] { "sname", "sid" },
            new Boolean[] { false, true });

      expectBadSyntax("select sid from student order sid");
      expectBadSyntax("select sid from student order by");
      expectBadSyntax("select sid from student order by sid,");

      System.out.println(
            "All " + checksRun + " ORDER BY parser checks passed.");
   }

   private static void checkOrder(QueryData data, String[] fields,
                                  Boolean[] directions) {
      check(data.hasOrderBy(), "ORDER BY should request sorting");
      check(data.orderFields().equals(Arrays.asList(fields)),
            "Unexpected ORDER BY fields: " + data.orderFields());
      check(data.orderDirections().equals(Arrays.asList(directions)),
            "Unexpected ORDER BY directions: "
                  + data.orderDirections());
   }

   private static void expectBadSyntax(String sql) {
      try {
         new Parser(sql).query();
         throw new AssertionError("Expected invalid syntax for: " + sql);
      }
      catch (BadSyntaxException expected) {
         checksRun++;
      }
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
