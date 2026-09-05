package simpledb.query;

import java.util.HashMap;
import java.util.Map;

import simpledb.plan.Plan;
import simpledb.record.Schema;

/**
 * Deterministic tests for comparison terms.
 *
 * This test uses lightweight Scan and Plan implementations, so it does not
 * create a database or depend on records left behind by an earlier test run.
 */
public class TermTest {
   private static int checksRun;

   public static void main(String[] args) {
      testIntegerComparisons();
      testStringComparisons();
      testFieldExpressions();
      testEqualityDetection();
      testEqualityOnlyIndexSafety();
      testPredicateEqualityOnlySafety();
      testMixedPredicateSafety();
      testReductionFactors();
      testBackwardCompatibleConstructor();
      testUnsupportedOperator();

      System.out.println("All " + checksRun + " Term checks passed.");
   }

   private static void testIntegerComparisons() {
      checkInt(10, "=", 10, true);
      checkInt(10, "=", 20, false);

      checkInt(10, "<", 20, true);
      checkInt(20, "<", 10, false);
      checkInt(10, "<", 10, false);

      checkInt(10, "<=", 20, true);
      checkInt(10, "<=", 10, true);
      checkInt(20, "<=", 10, false);

      checkInt(20, ">", 10, true);
      checkInt(10, ">", 20, false);
      checkInt(10, ">", 10, false);

      checkInt(20, ">=", 10, true);
      checkInt(10, ">=", 10, true);
      checkInt(10, ">=", 20, false);

      checkInt(10, "!=", 20, true);
      checkInt(10, "!=", 10, false);
      checkInt(10, "<>", 20, true);
      checkInt(10, "<>", 10, false);
   }

   private static void testStringComparisons() {
      checkString("alice", "=", "alice", true);
      checkString("alice", "=", "bob", false);

      checkString("alice", "<", "bob", true);
      checkString("bob", "<", "alice", false);
      checkString("alice", "<=", "alice", true);

      checkString("bob", ">", "alice", true);
      checkString("alice", ">", "bob", false);
      checkString("bob", ">=", "bob", true);

      checkString("alice", "!=", "bob", true);
      checkString("alice", "!=", "alice", false);
      checkString("alice", "<>", "bob", true);
      checkString("alice", "<>", "alice", false);
   }

   private static void testFieldExpressions() {
      TestScan scan = new TestScan();
      scan.set("A", new Constant(10));
      scan.set("B", new Constant(20));
      scan.set("FIRST", new Constant("alice"));
      scan.set("SECOND", new Constant("bob"));

      checkTerm(new Term(field("A"), "<", field("B")), scan, true,
            "A < B");
      checkTerm(new Term(field("B"), "<", field("A")), scan, false,
            "B < A");
      checkTerm(new Term(field("A"), ">=", constant(10)), scan, true,
            "A >= 10");
      checkTerm(new Term(constant(20), ">", field("A")), scan, true,
            "20 > A");
      checkTerm(new Term(field("FIRST"), "<", field("SECOND")), scan, true,
            "FIRST < SECOND");
      checkTerm(new Term(field("FIRST"), "<>", constant("alice")), scan, false,
            "FIRST <> 'alice'");
   }

   private static void testEqualityDetection() {
      Term fieldEqualsConstant = new Term(field("A"), "=", constant(10));
      Constant value = fieldEqualsConstant.equatesWithConstant("A");
      check(value != null && value.equals(new Constant(10)),
            "A = 10 should equate A with 10");

      Term constantEqualsField = new Term(constant(10), "=", field("A"));
      value = constantEqualsField.equatesWithConstant("A");
      check(value != null && value.equals(new Constant(10)),
            "10 = A should equate A with 10");

      check(new Term(field("A"), ">", constant(10))
                  .equatesWithConstant("A") == null,
            "A > 10 must not be treated as an equality");
      check(new Term(field("A"), "!=", constant(10))
                  .equatesWithConstant("A") == null,
            "A != 10 must not be treated as an equality");
      check(new Term(field("A"), "<>", constant(10))
                  .equatesWithConstant("A") == null,
            "A <> 10 must not be treated as an equality");

      Term fieldsEqual = new Term(field("A"), "=", field("B"));
      check("B".equals(fieldsEqual.equatesWithField("A")),
            "A = B should equate A with B");

      Term reversedFieldsEqual = new Term(field("B"), "=", field("A"));
      check("B".equals(reversedFieldsEqual.equatesWithField("A")),
            "B = A should equate A with B");

      check(new Term(field("A"), "<", field("B"))
                  .equatesWithField("A") == null,
            "A < B must not be treated as an equality");
      check(new Term(field("A"), "!=", field("B"))
                  .equatesWithField("A") == null,
            "A != B must not be treated as an equality");
   }

   private static void testEqualityOnlyIndexSafety() {
      String[] inequalities = { "<", "<=", ">", ">=", "!=", "<>" };

      for (String operator : inequalities) {
         Term fieldConstant =
               new Term(field("A"), operator, constant(10));
         check(fieldConstant.equatesWithConstant("A") == null,
               "A " + operator + " 10 must not support an index lookup");

         Term constantField =
               new Term(constant(10), operator, field("A"));
         check(constantField.equatesWithConstant("A") == null,
               "10 " + operator + " A must not support an index lookup");

         Term fieldField =
               new Term(field("A"), operator, field("B"));
         check(fieldField.equatesWithField("A") == null,
               "A " + operator + " B must not support an index join on A");
         check(fieldField.equatesWithField("B") == null,
               "A " + operator + " B must not support an index join on B");
      }
   }

   private static void testPredicateEqualityOnlySafety() {
      Predicate inequality = new Predicate(
            new Term(field("A"), ">", constant(10)));
      check(inequality.equatesWithConstant("A") == null,
            "Predicate containing A > 10 must not expose an equality");

      Predicate inequalityJoin = new Predicate(
            new Term(field("A"), "<", field("B")));
      check(inequalityJoin.equatesWithField("A") == null,
            "Predicate containing A < B must not expose an equality join");
   }

   private static void testMixedPredicateSafety() {
      Predicate pred = new Predicate(
            new Term(field("A"), ">", constant(10)));
      pred.conjoinWith(new Predicate(
            new Term(field("A"), "=", constant(20))));

      Constant equality = pred.equatesWithConstant("A");
      check(new Constant(20).equals(equality),
            "A > 10 and A = 20 should expose only the equality value");
   }

   private static void testReductionFactors() {
      TestPlan plan = new TestPlan();
      plan.setDistinctValues("A", 50);
      plan.setDistinctValues("B", 20);

      checkReduction(new Term(field("A"), "=", constant(10)), plan, 50,
            "A = 10");
      checkReduction(new Term(constant(10), "=", field("A")), plan, 50,
            "10 = A");
      checkReduction(new Term(field("A"), "=", field("B")), plan, 50,
            "A = B");

      checkReduction(new Term(field("A"), "<", constant(10)), plan, 2,
            "A < 10");
      checkReduction(new Term(field("A"), ">=", constant(10)), plan, 2,
            "A >= 10");
      checkReduction(new Term(field("A"), "!=", field("B")), plan, 2,
            "A != B");

      checkReduction(new Term(constant(10), "<", constant(20)), null, 1,
            "10 < 20");
      checkReduction(new Term(constant(20), "<", constant(10)), null,
            Integer.MAX_VALUE, "20 < 10");
      checkReduction(new Term(constant("alice"), "<", constant("bob")),
            null, 1, "'alice' < 'bob'");
      checkReduction(new Term(constant("alice"), "=", constant("bob")),
            null, Integer.MAX_VALUE, "'alice' = 'bob'");
   }

   private static void testBackwardCompatibleConstructor() {
      Term equal = new Term(constant(10), constant(10));
      checkTerm(equal, null, true,
            "the two-argument constructor should use equality");
      check("10=10".equals(equal.toString()),
            "the two-argument constructor should display equality");
   }

   private static void testUnsupportedOperator() {
      Term invalid = new Term(constant(10), "?", constant(20));

      try {
         invalid.isSatisfied(null);
         throw new AssertionError("An unsupported operator should fail");
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static void checkInt(int lhs, String operator, int rhs,
         boolean expected) {
      checkTerm(new Term(constant(lhs), operator, constant(rhs)), null, expected,
            lhs + " " + operator + " " + rhs);
   }

   private static void checkString(String lhs, String operator, String rhs,
         boolean expected) {
      checkTerm(new Term(constant(lhs), operator, constant(rhs)), null, expected,
            "'" + lhs + "' " + operator + " '" + rhs + "'");
   }

   private static void checkTerm(Term term, Scan scan, boolean expected,
         String description) {
      boolean actual = term.isSatisfied(scan);
      check(actual == expected,
            description + ": expected " + expected + ", but got " + actual);
   }

   private static void checkReduction(Term term, Plan plan, int expected,
         String description) {
      int actual = term.reductionFactor(plan);
      check(actual == expected,
            description + " reduction factor: expected " + expected
                  + ", but got " + actual);
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }

   private static Expression field(String name) {
      return new Expression(name);
   }

   private static Expression constant(int value) {
      return new Expression(new Constant(value));
   }

   private static Expression constant(String value) {
      return new Expression(new Constant(value));
   }

   private static class TestScan implements Scan {
      private Map<String, Constant> values = new HashMap<String, Constant>();

      public void set(String fldname, Constant value) {
         values.put(fldname, value);
      }

      @Override
      public void beforeFirst() {
      }

      @Override
      public boolean next() {
         return false;
      }

      @Override
      public int getInt(String fldname) {
         return getVal(fldname).asInt();
      }

      @Override
      public String getString(String fldname) {
         return getVal(fldname).asString();
      }

      @Override
      public Constant getVal(String fldname) {
         return values.get(fldname);
      }

      @Override
      public boolean hasField(String fldname) {
         return values.containsKey(fldname);
      }

      @Override
      public void close() {
      }
   }

   private static class TestPlan implements Plan {
      private Map<String, Integer> distinctValues =
            new HashMap<String, Integer>();

      public void setDistinctValues(String fldname, int count) {
         distinctValues.put(fldname, count);
      }

      @Override
      public Scan open() {
         return null;
      }

      @Override
      public int blocksAccessed() {
         return 0;
      }

      @Override
      public int recordsOutput() {
         return 0;
      }

      @Override
      public int distinctValues(String fldname) {
         Integer count = distinctValues.get(fldname);
         if (count == null)
            throw new IllegalArgumentException("Unknown field: " + fldname);
         return count;
      }

      @Override
      public Schema schema() {
         return new Schema();
      }
   }
}
