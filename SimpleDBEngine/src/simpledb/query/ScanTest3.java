package simpledb.query;

import simpledb.record.Layout;
import simpledb.record.Schema;
import simpledb.record.TableScan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/**
 * Integration tests for non-equality predicates evaluated by SelectScan.
 *
 * The test data is deterministic, and records left by an earlier run are
 * removed before each run.
 */
public class ScanTest3 {
   private static final String TABLE_NAME = "T";
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      SimpleDB db = new SimpleDB("scantest3");
      Transaction tx = db.newTx();

      Schema schema = new Schema();
      schema.addIntField("A");
      schema.addStringField("B", 10);
      schema.addIntField("C");
      schema.addStringField("D", 10);
      Layout layout = new Layout(schema);

      loadTestData(tx, layout);

      testIntegerFieldComparisons(tx, layout);
      testStringFieldComparisons(tx, layout);
      testFieldToConstantComparisons(tx, layout);
      testConjoinedPredicate(tx, layout);

      tx.commit();
      System.out.println("All " + checksRun + " ScanTest3 checks passed.");
   }

   private static void loadTestData(Transaction tx, Layout layout) {
      UpdateScan table = new TableScan(tx, TABLE_NAME, layout);

      // Clear records from earlier runs so expected counts remain deterministic.
      table.beforeFirst();
      while (table.next())
         table.delete();

      insert(table, 5, "alice", 10, "bob");
      insert(table, 10, "bob", 10, "bob");
      insert(table, 15, "carol", 10, "bob");

      table.close();
   }

   private static void insert(UpdateScan table, int a, String b, int c,
         String d) {
      table.insert();
      table.setInt("A", a);
      table.setString("B", b);
      table.setInt("C", c);
      table.setString("D", d);
   }

   private static void testIntegerFieldComparisons(Transaction tx,
         Layout layout) {
      checkCount(tx, layout, predicate(field("A"), "<", field("C")), 1,
            "A < C");
      checkCount(tx, layout, predicate(field("A"), "<=", field("C")), 2,
            "A <= C");
      checkCount(tx, layout, predicate(field("A"), ">", field("C")), 1,
            "A > C");
      checkCount(tx, layout, predicate(field("A"), ">=", field("C")), 2,
            "A >= C");
      checkCount(tx, layout, predicate(field("A"), "!=", field("C")), 2,
            "A != C");
      checkCount(tx, layout, predicate(field("A"), "<>", field("C")), 2,
            "A <> C");
   }

   private static void testStringFieldComparisons(Transaction tx,
         Layout layout) {
      checkCount(tx, layout, predicate(field("B"), "<", field("D")), 1,
            "B < D");
      checkCount(tx, layout, predicate(field("B"), ">=", field("D")), 2,
            "B >= D");
      checkCount(tx, layout, predicate(field("B"), "!=", field("D")), 2,
            "B != D");
      checkCount(tx, layout, predicate(field("B"), "<>", field("D")), 2,
            "B <> D");
   }

   private static void testFieldToConstantComparisons(Transaction tx,
         Layout layout) {
      checkCount(tx, layout, predicate(field("A"), ">", constant(10)), 1,
            "A > 10");
      checkCount(tx, layout,
            predicate(field("B"), "<>", constant("bob")), 2,
            "B <> 'bob'");
   }

   private static void testConjoinedPredicate(Transaction tx, Layout layout) {
      Predicate pred = predicate(field("A"), ">=", constant(10));
      pred.conjoinWith(predicate(field("B"), "<>", constant("bob")));

      checkCount(tx, layout, pred, 1, "A >= 10 and B <> 'bob'");
   }

   private static Predicate predicate(Expression lhs, String operator,
         Expression rhs) {
      return new Predicate(new Term(lhs, operator, rhs));
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

   private static void checkCount(Transaction tx, Layout layout,
         Predicate pred, int expected, String description) {
      Scan table = new TableScan(tx, TABLE_NAME, layout);
      Scan selected = new SelectScan(table, pred);
      int actual = 0;

      while (selected.next())
         actual++;

      selected.close();
      checksRun++;

      if (actual != expected) {
         throw new AssertionError(
               description + ": expected " + expected
                     + " records, but found " + actual);
      }

      System.out.println("PASS: " + description + " -> " + actual);
   }
}
