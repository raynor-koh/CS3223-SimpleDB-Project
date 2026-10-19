package simpledb.parse;

import java.util.Arrays;

/** Deterministic checks for EXPLAIN parsing. */
public class ExplainParserTest {
   private static int checksRun;

   public static void main(String[] args) {
      QueryData data = new Parser(
            "explain select distinct dept from course order by dept desc")
            .explain();
      check(data.isDistinct(), "EXPLAIN should preserve DISTINCT");
      check(data.fields().equals(Arrays.asList("dept")),
            "EXPLAIN should parse the wrapped select list");
      check(data.orderFields().equals(Arrays.asList("dept")),
            "EXPLAIN should parse ORDER BY");

      QueryData uppercase = new Parser("EXPLAIN SELECT dept FROM course")
            .explain();
      check(!uppercase.isDistinct(), "EXPLAIN should support ordinary SELECT");
      check(uppercase.toString().equals("select dept from course"),
            "EXPLAIN should return the parsed query data");

      expectBadSyntax("explain");
      expectBadSyntax("explain update course set dept = 'cs'");
      expectBadSyntax("select dept from course");

      System.out.println("All " + checksRun + " EXPLAIN parser checks passed.");
   }

   private static void expectBadSyntax(String sql) {
      try {
         new Parser(sql).explain();
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
