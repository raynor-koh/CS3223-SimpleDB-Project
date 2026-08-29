package simpledb.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import simpledb.index.btree.BTreeIndex;
import simpledb.index.hash.HashIndex;
import simpledb.metadata.IndexInfo;
import simpledb.metadata.MetadataMgr;
import simpledb.plan.Plan;
import simpledb.plan.Planner;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/**
 * Integration tests for typed hash and B-tree indexes.
 *
 * The test database is created below the operating system's temporary
 * directory, so this test does not read or modify the shared studentdb.
 */
public class TypedIndexIntegrationTest {
   private static int checksRun;

   public static void main(String[] args) throws Exception {
      Path testRoot = Files.createTempDirectory("simpledb-typed-index-");
      String dbname = testRoot.resolve("database").toString();

      createTypedIndexes(dbname);
      testPersistedTypesAndImplementations(dbname);

      System.out.println(
            "All " + checksRun + " typed-index integration checks passed.");
   }

   private static void createTypedIndexes(String dbname) {
      SimpleDB db = new SimpleDB(dbname);
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      planner.executeUpdate(
            "create table hashitems(id int, keyval int)", tx);
      planner.executeUpdate(
            "create table btreeitems(id int, keyval int)", tx);
      planner.executeUpdate(
            "create index idx_hash_key on hashitems(keyval) using hash", tx);
      planner.executeUpdate(
            "create index idx_btree_key on btreeitems(keyval) using btree", tx);

      tx.commit();
   }

   private static void testPersistedTypesAndImplementations(String dbname) {
      // Opening a new SimpleDB instance verifies that the index type was
      // persisted in idxcat rather than retained only in Java objects.
      SimpleDB db = new SimpleDB(dbname);
      MetadataMgr mdm = db.mdMgr();
      Planner planner = db.planner();
      Transaction tx = db.newTx();

      IndexInfo hashInfo = getIndexInfo(mdm, tx, "hashitems", "keyval");
      IndexInfo btreeInfo = getIndexInfo(mdm, tx, "btreeitems", "keyval");

      check("hash".equals(hashInfo.indexType()),
            "Hash index type was not persisted");
      check("btree".equals(btreeInfo.indexType()),
            "B-tree index type was not persisted");

      Index hashIndex = hashInfo.open();
      check(hashIndex instanceof HashIndex,
            "Hash metadata did not open a HashIndex");
      hashIndex.close();

      Index btreeIndex = btreeInfo.open();
      check(btreeIndex instanceof BTreeIndex,
            "B-tree metadata did not open a BTreeIndex");
      btreeIndex.close();

      testUpdateConsistency(planner, mdm, tx, "hashitems");
      testUpdateConsistency(planner, mdm, tx, "btreeitems");

      insertQueryData(planner, tx, "hashitems");
      insertQueryData(planner, tx, "btreeitems");

      assertIndexCount(mdm, tx, "hashitems", 10, 1);
      assertIndexCount(mdm, tx, "btreeitems", 10, 1);

      testEqualityAndInequalityQueries(planner, tx, "hashitems");
      testEqualityAndInequalityQueries(planner, tx, "btreeitems");

      tx.commit();
   }

   private static IndexInfo getIndexInfo(MetadataMgr mdm, Transaction tx,
         String tableName, String fieldName) {
      Map<String, IndexInfo> indexes = mdm.getIndexInfo(tableName, tx);
      IndexInfo info = indexes.get(fieldName);
      check(info != null,
            "Missing index metadata for " + tableName + "." + fieldName);
      return info;
   }

   private static void testUpdateConsistency(Planner planner, MetadataMgr mdm,
         Transaction tx, String tableName) {
      check(planner.executeUpdate(
            "insert into " + tableName + "(id, keyval) values (1, 10)", tx)
            == 1, "Insert count was incorrect for " + tableName);
      assertIndexCount(mdm, tx, tableName, 10, 1);

      check(planner.executeUpdate(
            "update " + tableName + " set keyval = 20 where id = 1", tx)
            == 1, "Update count was incorrect for " + tableName);
      assertIndexCount(mdm, tx, tableName, 10, 0);
      assertIndexCount(mdm, tx, tableName, 20, 1);

      check(planner.executeUpdate(
            "delete from " + tableName + " where id = 1", tx)
            == 1, "Delete count was incorrect for " + tableName);
      assertIndexCount(mdm, tx, tableName, 20, 0);
   }

   private static void insertQueryData(Planner planner, Transaction tx,
         String tableName) {
      int[][] rows = {
            { 11, 5 },
            { 12, 10 },
            { 13, 15 },
            { 14, 20 }
      };

      for (int[] row : rows) {
         int count = planner.executeUpdate(
               "insert into " + tableName + "(id, keyval) values ("
                     + row[0] + ", " + row[1] + ")",
               tx);
         check(count == 1,
               "Insert count was incorrect for " + tableName);
      }
   }

   private static void assertIndexCount(MetadataMgr mdm, Transaction tx,
         String tableName, int key, int expected) {
      IndexInfo info = getIndexInfo(mdm, tx, tableName, "keyval");
      Index index = info.open();
      int actual = 0;

      index.beforeFirst(new Constant(key));
      while (index.next())
         actual++;
      index.close();

      check(actual == expected,
            tableName + " index lookup for " + key + ": expected "
                  + expected + ", but got " + actual);
   }

   private static void testEqualityAndInequalityQueries(Planner planner,
         Transaction tx, String tableName) {
      assertQuery(planner, tx, tableName, "keyval = 15", ids(13));
      assertQuery(planner, tx, tableName, "15 = keyval", ids(13));
      assertQuery(planner, tx, tableName, "keyval < 15", ids(11, 12));
      assertQuery(planner, tx, tableName, "keyval <= 15", ids(11, 12, 13));
      assertQuery(planner, tx, tableName, "keyval > 10", ids(13, 14));
      assertQuery(planner, tx, tableName, "keyval >= 10", ids(12, 13, 14));
      assertQuery(planner, tx, tableName, "keyval != 10", ids(11, 13, 14));
      assertQuery(planner, tx, tableName, "keyval <> 10", ids(11, 13, 14));
   }

   private static void assertQuery(Planner planner, Transaction tx,
         String tableName, String predicate, Set<Integer> expectedIds) {
      String sql = "select id from " + tableName + " where " + predicate;
      Plan plan = planner.createQueryPlan(sql, tx);
      Scan scan = plan.open();
      Set<Integer> actualIds = new TreeSet<Integer>();

      while (scan.next())
         actualIds.add(scan.getInt("id"));
      scan.close();

      check(actualIds.equals(expectedIds),
            sql + ": expected " + expectedIds + ", but got " + actualIds);
   }

   private static Set<Integer> ids(Integer... values) {
      return new TreeSet<Integer>(Arrays.asList(values));
   }

   private static void check(boolean condition, String message) {
      checksRun++;
      if (!condition)
         throw new AssertionError(message);
   }
}
