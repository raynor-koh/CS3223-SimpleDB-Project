package simpledb.multibuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.plan.TablePlan;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Integration checks for the partitioned hash join operator. */
public class HashJoinIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-hash-join-");
      SimpleDB db = new SimpleDB(testRoot.resolve("database").toString());
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      createIntegerTables(planner, tx);
      createStringTables(planner, tx);
      testIntegerJoin(db, tx);
      testStringJoin(db, tx);
      testEmptyJoin(db, tx);
      testValidation(db, tx);

      tx.commit();
      System.out.println("All " + checksRun
            + " hash join integration checks passed.");
   }

   private static void createIntegerTables(Planner planner, Transaction tx) {
      planner.executeUpdate("create table leftrows(lkey int, lvalue varchar(10))", tx);
      planner.executeUpdate("create table rightrows(rkey int, rvalue varchar(10))", tx);
      planner.executeUpdate("create table nomatches(nkey int, nvalue varchar(10))", tx);
      insert(planner, tx, "leftrows", "lkey, lvalue", "1, 'a'");
      insert(planner, tx, "leftrows", "lkey, lvalue", "1, 'b'");
      insert(planner, tx, "leftrows", "lkey, lvalue", "2, 'c'");
      insert(planner, tx, "leftrows", "lkey, lvalue", "4, 'd'");
      insert(planner, tx, "leftrows", "lkey, lvalue", "-1, 'negative'");
      insert(planner, tx, "rightrows", "rkey, rvalue", "1, 'x'");
      insert(planner, tx, "rightrows", "rkey, rvalue", "1, 'y'");
      insert(planner, tx, "rightrows", "rkey, rvalue", "2, 'w'");
      insert(planner, tx, "rightrows", "rkey, rvalue", "3, 'z'");
      insert(planner, tx, "rightrows", "rkey, rvalue", "-1, 'neg-match'");
      insert(planner, tx, "nomatches", "nkey, nvalue", "99, 'none'");
   }

   private static void createStringTables(Planner planner, Transaction tx) {
      planner.executeUpdate("create table leftstrings(lcode varchar(10), lname varchar(10))", tx);
      planner.executeUpdate("create table rightstrings(rcode varchar(10), rname varchar(10))", tx);
      insert(planner, tx, "leftstrings", "lcode, lname", "'aa', 'left-a'");
      insert(planner, tx, "leftstrings", "lcode, lname", "'bb', 'left-b'");
      insert(planner, tx, "rightstrings", "rcode, rname", "'aa', 'right-a'");
      insert(planner, tx, "rightstrings", "rcode, rname", "'cc', 'right-c'");
   }

   private static void insert(Planner planner, Transaction tx, String table,
                              String fields, String values) {
      int count = planner.executeUpdate("insert into " + table + "(" + fields
            + ") values (" + values + ")", tx);
      check(count == 1, "Insert count should be one");
   }

   private static void testIntegerJoin(SimpleDB db, Transaction tx) {
      int buffersBefore = tx.availableBuffs();
      Plan plan = new HashJoinPlan(tx, table(db, tx, "leftrows"),
            table(db, tx, "rightrows"), "lkey", "rkey");
      check(plan.blocksAccessed() >= 0,
            "Hash join should provide a nonnegative I/O estimate");

      Scan scan = plan.open();
      Set<String> expected = new HashSet<String>(Arrays.asList(
            "a-x", "a-y", "b-x", "b-y", "c-w", "negative-neg-match"));
      check(readPairs(scan, "lvalue", "rvalue").equals(expected),
            "Hash join must preserve duplicate-key many-to-many matches");
      scan.beforeFirst();
      check(readPairs(scan, "lvalue", "rvalue").equals(expected),
            "Hash join beforeFirst should rebuild a correct scan");
      scan.close();
      check(tx.availableBuffs() == buffersBefore,
            "Hash join should release every pinned buffer on close");
   }

   private static void testStringJoin(SimpleDB db, Transaction tx) {
      Plan plan = new HashJoinPlan(tx, table(db, tx, "leftstrings"),
            table(db, tx, "rightstrings"), "lcode", "rcode");
      Scan scan = plan.open();
      check(readPairs(scan, "lname", "rname").equals(
            new HashSet<String>(Arrays.asList("left-a-right-a"))),
            "Hash join should support string keys");
      scan.close();
   }

   private static void testEmptyJoin(SimpleDB db, Transaction tx) {
      Plan plan = new HashJoinPlan(tx, table(db, tx, "leftrows"),
            table(db, tx, "nomatches"), "lkey", "nkey");
      Scan scan = plan.open();
      check(!scan.next(), "Hash join with no matching keys should be empty");
      scan.close();

      try {
         new HashJoinPlan(tx, table(db, tx, "leftrows"),
               table(db, tx, "rightstrings"), "lkey", "rcode");
         throw new AssertionError("Incompatible hash keys should be rejected");
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static void testValidation(SimpleDB db, Transaction tx) {
      try {
         new HashJoinPlan(tx, table(db, tx, "leftrows"),
               table(db, tx, "rightrows"), "missing", "rkey");
         throw new AssertionError("Missing join key should be rejected");
      }
      catch (IllegalArgumentException expected) {
         checksRun++;
      }
   }

   private static Set<String> readPairs(Scan scan, String left, String right) {
      Set<String> result = new HashSet<String>();
      while (scan.next())
         result.add(scan.getString(left) + "-" + scan.getString(right));
      return result;
   }

   private static Plan table(SimpleDB db, Transaction tx, String name) {
      return new TablePlan(tx, name, db.mdMgr());
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
