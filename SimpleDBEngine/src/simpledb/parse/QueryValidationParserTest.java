package simpledb.parse;

/** Regression checks for complete statements, exact integers and diagnostics. */
public class QueryValidationParserTest {
   private static int checksRun;

   public static void main(String[] args) {
      invalid("select from items", "SELECT", "field");
      invalid("select id from", "FROM", "identifier", "end of input");
      invalid("select id from items,", "FROM", "identifier");
      invalid("select id from items where", "WHERE", "integer");
      invalid("select id from items group by", "GROUP BY", "identifier");
      invalid("select id from items order by", "ORDER BY", "identifier");
      invalid("select id from items junk", "FROM", "end of statement", "junk");
      invalid("select sum(*) from items", "SELECT", "SUM", "field");
      invalid("select count(id, grp) from items", "SELECT", ")", ",");
      invalid("select id from items where id ! 1", "WHERE", "'='", "'1'");
      invalid("select id from items where id = 1.5", "WHERE", "integer", "decimal");
      invalid("select id from items where id = 2147483647.0000000001",
            "WHERE", "integer", "decimal");
      invalid("select id from items where id = 12abc", "WHERE", "integer", "12abc");
      for (String value : new String[] {"2147483648", "-2147483649",
            "999999999999999999999999999999999999999"})
         invalid("select id from items where id = " + value, "WHERE", "out of range", value);
      invalid("select id from items where label = 'unterminated",
            "WHERE", "closing quote", "unterminated");
      invalid("select id from items where label = 'escaped\\'",
            "WHERE", "closing quote");
      invalid("select id from items where label = 'broken\nstring'", "WHERE", "closing quote");
      invalid("select id from items where label = 'ok' and label = 'broken",
            "WHERE", "closing quote", "broken");

      check(new Parser("select id from items where id = -2147483648").query()
            .pred().equatesWithConstant("id").asInt() == Integer.MIN_VALUE, "Minimum integer changed");
      check(new Parser("select id from items where id = 2147483647").query()
            .pred().equatesWithConstant("id").asInt() == Integer.MAX_VALUE, "Maximum integer changed");
      for (String value : new String[] {"''", "'a/b'", "'a\\'b'", "'a\\\\'",
            "'line\\nnext'", "'line\\rnext'", "'tab\\tvalue'"}) {
         QueryData parsed = new Parser("select id from items where label = " + value).query();
         check(parsed != null, "Valid string rejected: " + value);
         check(new Parser(parsed.toString()).query().pred().equatesWithConstant("label")
               .equals(parsed.pred().equatesWithConstant("label")), "Quoted view literal changed: " + value);
      }
      check(new Parser("select id from items / ignored 'unclosed").query() != null,
            "Existing slash comment behavior changed");
      Lexer lexer = new Lexer("row12 -42");
      check(lexer.matchId() && lexer.eatId().equals("row12"), "Identifier digits changed");
      check(lexer.matchIntConstant() && lexer.eatIntConstant() == -42 && lexer.matchEnd(),
            "Signed integer token changed");
      check(new Parser("select item-id from item-table").query().fields().get(0).equals("item-id"),
            "Existing identifier hyphens changed");
      try {
         new Parser("insert into items(id) values (1) junk").updateCmd();
         throw new AssertionError("Trailing update tokens were accepted");
      } catch (BadSyntaxException e) { fragments(e, "VALUES", "junk", "end of statement"); }
      try {
         new Parser("explain select id from items junk").explain();
         throw new AssertionError("Trailing EXPLAIN tokens were accepted");
      } catch (BadSyntaxException e) { fragments(e, "FROM", "junk", "end of statement"); }
      System.out.println("All " + checksRun + " query validation parser checks passed.");
   }

   private static void invalid(String sql, String... expected) {
      try {
         new Parser(sql).query();
         throw new AssertionError("Invalid SQL accepted: " + sql);
      } catch (BadSyntaxException e) { fragments(e, expected); }
   }

   private static void fragments(Exception e, String... expected) {
      check(e.getMessage() != null, "Missing syntax diagnostic");
      for (String fragment : expected)
         check(e.getMessage().contains(fragment), "Expected '" + fragment + "' in " + e.getMessage());
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition) throw new AssertionError(message);
   }
}
