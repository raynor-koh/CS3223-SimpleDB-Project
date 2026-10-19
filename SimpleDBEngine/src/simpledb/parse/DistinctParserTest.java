package simpledb.parse;

import java.util.Arrays;

/** Deterministic checks for SELECT DISTINCT parsing and query representation. */
public class DistinctParserTest {
   private static int checksRun;

   public static void main(String[] args) {
      QueryData distinct = new Parser(
            "select distinct dept, credits from course order by dept desc")
            .query();
      check(distinct.isDistinct(), "DISTINCT query should be identified");
      check(distinct.fields().equals(Arrays.asList("dept", "credits")),
            "DISTINCT should preserve select-list fields");
      check(distinct.toString().equals(
            "select distinct dept, credits from course order by dept desc"),
            "DISTINCT query should round-trip: " + distinct);

      QueryData uppercase = new Parser(
            "SELECT DISTINCT DEPT FROM COURSE").query();
      check(uppercase.isDistinct(), "DISTINCT should be case-insensitive");
      check(uppercase.toString().equals("select distinct dept from course"),
            "Uppercase DISTINCT should use canonical rendering");

      QueryData ordinary = new Parser("select dept from course").query();
      check(!ordinary.isDistinct(), "Ordinary SELECT should not be distinct");
      check(ordinary.toString().equals("select dept from course"),
            "Ordinary SELECT rendering should be unchanged");

      expectBadSyntax("select distinct from course");
      expectBadSyntax("select dept distinct from course");
      expectBadSyntax("select distinct from distinct");

      System.out.println("All " + checksRun
            + " DISTINCT parser checks passed.");
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
