package simpledb.parse;

import java.util.Arrays;

import simpledb.record.Schema;

/** Deterministic checks for aggregate SELECT-list and GROUP BY parsing. */
public class AggregateParserTest {
   private static int checksRun;

   public static void main(String[] args) {
      QueryData grouped = new Parser(
            "select dept, sum(credits), count(*), min(name), max(name) "
            + "from course where credits >= 1 group by dept order by dept")
            .query();

      check(grouped.hasAggregates(), "Aggregate query should be identified");
      check(grouped.hasGroupBy(), "GROUP BY should be identified");
      check(grouped.groupFields().equals(Arrays.asList("dept")),
            "Unexpected group fields");
      check(grouped.fields().equals(Arrays.asList("dept", "sumofcredits",
            "countofall", "minofname", "maxofname")),
            "Unexpected output names: " + grouped.fields());
      check(grouped.selectItems().get(1).aggregate()
            == SelectItem.Aggregate.SUM, "SUM item was not parsed");
      check(grouped.selectItems().get(2).argument() == null,
            "COUNT(*) should not have an input field");
      check(grouped.toString().equals(
            "select dept, sum(credits), count(*), min(name), max(name) "
            + "from course where credits>=1 group by dept order by dept asc"),
            "Unexpected query rendering: " + grouped);

      QueryData plain = new Parser("select sid from student").query();
      check(!plain.hasAggregates(), "Plain query should not have aggregates");
      check(!plain.hasGroupBy(), "Plain query should not have GROUP BY");

      QueryData multiGroup = new Parser(
            "select dept, name, count(*) from course where credits > 0 "
            + "group by dept, name order by dept desc, name asc").query();
      check(multiGroup.groupFields().equals(Arrays.asList("dept", "name")),
            "Multiple GROUP BY fields should preserve their order");
      check(multiGroup.orderFields().equals(Arrays.asList("dept", "name")),
            "ORDER BY should follow GROUP BY");
      check(multiGroup.toString().equals(
            "select dept, name, count(*) from course where credits>0 "
            + "group by dept, name order by dept desc, name asc"),
            "Unexpected multi-group rendering: " + multiGroup);

      expectBadSyntax("select sum(*) from course");
      expectBadSyntax("select count() from course");
      expectBadSyntax("select count( from course");
      expectBadSyntax("select dept from course group by");
      expectBadSyntax("select agv(credits) from course");

      Schema schema = new Schema();
      schema.addStringField("dept", 10);
      schema.addIntField("credits");
      schema.addStringField("name", 20);
      grouped.validateAgainst(schema);
      checksRun++;

      expectInvalidSemantics("select dept, sum(credits) from course", schema);
      expectInvalidSemantics("select sum(name) from course", schema);
      expectInvalidSemantics("select count(missing) from course", schema);
      expectInvalidSemantics("select sum(credits) from course group by missing",
            schema);

      System.out.println("All " + checksRun
            + " aggregate parser checks passed.");
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

   private static void expectInvalidSemantics(String sql, Schema schema) {
      try {
         new Parser(sql).query().validateAgainst(schema);
         throw new AssertionError("Expected invalid semantics for: " + sql);
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
