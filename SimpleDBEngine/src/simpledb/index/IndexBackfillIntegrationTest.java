package simpledb.index;

import java.nio.file.Files;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

import simpledb.metadata.IndexInfo;
import simpledb.file.BlockId;
import simpledb.plan.Plan;
import simpledb.plan.PlanExplainer;
import simpledb.plan.Planner;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.TableScan;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

/** Index creation must preserve existing live rows, including duplicate keys. */
public class IndexBackfillIntegrationTest {
   private static int checks;

   public static void main(String[] args) throws Exception {
      testBtreeSplitResources();
      for (String method : new String[] { "hash", "btree" }) {
         for (String type : new String[] { "int", "varchar(12)", "time" })
            exercise(method, type);
         testMetadataCreation(method);
      }
      System.out.println("All " + checks + " index-backfill integration checks passed.");
   }

   private static void testBtreeSplitResources() throws Exception {
      String path = Files.createTempDirectory("simpledb-btree-splits-").resolve("db").toString();
      SimpleDB db = new SimpleDB(path);
      Transaction tx = db.newTx();
      try {
         for (boolean duplicates : new boolean[] {false, true}) {
            String tableName = duplicates ? "splitdups" : "splitunique";
            String indexName = duplicates ? "split_d" : "split_u";
            int rows = duplicates ? 256 : 512;
            db.planner().executeUpdate("create table " + tableName + "(id int, keyval int)", tx);
            db.planner().executeUpdate("create index " + indexName + " on " + tableName
                  + "(keyval) using btree", tx);
            Index index = db.mdMgr().getIndexInfo(tableName, tx).get("keyval").open();
            TableScan table = new TableScan(tx, tableName, db.mdMgr().getLayout(tableName, tx));
            try {
               for (int id = 0; id < rows; id++) {
                  table.insert();
                  table.setInt("id", id);
                  table.setInt("keyval", duplicates ? 7 : id);
                  int before = tx.availableBuffs();
                  index.insert(new Constant(duplicates ? 7 : id), table.getRid());
                  check(tx.availableBuffs() == before,
                        "B-tree split leaked a buffer at inserted row " + id + ", duplicates=" + duplicates);
               }
            } finally {
               index.close();
               table.close();
            }
            check(tx.availableBuffs() == SimpleDB.BUFFER_SIZE, "B-tree insert resources were not restored");
            if (duplicates)
               check(tx.size(indexName + "leaf") > SimpleDB.BUFFER_SIZE,
                     "Duplicate fixture must exercise more overflow pages than available buffers");
            else {
               BlockId root = new BlockId(indexName + "dir", 0);
               tx.pin(root);
               try { check(tx.getInt(root, 0) > 0, "Unique fixture must split directories and grow the root"); }
               finally { tx.unpin(root); }
            }
         }
         verifySplitIndexes(db, tx);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
      db = new SimpleDB(path);
      tx = db.newTx();
      try {
         verifySplitIndexes(db, tx);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void verifySplitIndexes(SimpleDB db, Transaction tx) {
      for (boolean duplicates : new boolean[] {false, true}) {
         String name = duplicates ? "splitdups" : "splitunique";
         int rows = duplicates ? 256 : 512;
         int before = tx.availableBuffs();
         Index index = db.mdMgr().getIndexInfo(name, tx).get("keyval").open();
         TableScan table = new TableScan(tx, name, db.mdMgr().getLayout(name, tx));
         BitSet seen = new BitSet();
         try {
            for (int key = 0; key < (duplicates ? 1 : rows); key++) {
               index.beforeFirst(new Constant(duplicates ? 7 : key));
               while (index.next()) {
                  table.moveToRid(index.getDataRid());
                  int id = table.getInt("id");
                  check(id >= 0 && id < rows && !seen.get(id)
                        && table.getInt("keyval") == (duplicates ? 7 : key)
                        && (duplicates || id == key), "Incorrect or duplicate RID after B-tree splits");
                  seen.set(id);
               }
            }
            check(seen.cardinality() == rows, "B-tree splits lost indexed rows");
            index.beforeFirst(new Constant(-1));
            check(!index.next(), "Split index returned an absent key");
         } finally {
            index.close();
            table.close();
         }
         check(tx.availableBuffs() == before, "Split index probes leaked buffers");
      }
   }

   private static void testMetadataCreation(String method) throws Exception {
      SimpleDB db = new SimpleDB(Files.createTempDirectory("simpledb-backfill-api-")
            .resolve("db").toString());
      Transaction tx = db.newTx();
      try {
         db.planner().executeUpdate("create table items(id int, keyval int)", tx);
         Map<Integer, Integer> model = new LinkedHashMap<>();
         for (int id = 0; id < 3; id++) {
            insert(db.planner(), tx, "int", id, 0);
            model.put(id, 0);
         }
         int buffers = tx.availableBuffs();
         db.mdMgr().createIndex("apiidx", "items", "keyval", method, tx);
         check(tx.availableBuffs() == buffers, "Metadata index build leaked buffers");
         assertState(db, tx, "int", model);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void exercise(String method, String type) throws Exception {
      String path = Files.createTempDirectory("simpledb-backfill-").resolve("db").toString();
      Map<Integer, Integer> model = new LinkedHashMap<>();
      SimpleDB db = new SimpleDB(path);
      Transaction tx = db.newTx();
      try {
         Planner planner = db.planner();
         planner.executeUpdate("create table items(id int, keyval " + type + ")", tx);
         for (int id = 0; id < 48; id++) {
            int key = id % 5;
            insert(planner, tx, type, id, key);
            model.put(id, key);
         }
         // The build must skip deleted slots rather than indexing stale bytes.
         planner.executeUpdate("delete from items where id = 3", tx);
         planner.executeUpdate("delete from items where id = 9", tx);
         model.remove(3);
         model.remove(9);
         assertQueries(db, tx, type, model, false);
         planner.executeUpdate("create index lateidx on items(keyval) using " + method, tx);
         assertState(db, tx, type, model);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }

      db = new SimpleDB(path);
      tx = db.newTx();
      try {
         assertState(db, tx, type, model);
         insert(db.planner(), tx, type, 100, 1);
         model.put(100, 1);
         db.planner().executeUpdate("update items set keyval = " + literal(type, 4)
               + " where id = 0", tx);
         model.put(0, 4);
         db.planner().executeUpdate("delete from items where keyval = " + literal(type, 2), tx);
         model.values().removeIf(key -> key == 2);
         assertState(db, tx, type, model);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }

      db = new SimpleDB(path);
      tx = db.newTx();
      try {
         assertState(db, tx, type, model);
         tx.commit();
      } catch (RuntimeException | Error e) {
         tx.rollback();
         throw e;
      }
   }

   private static void insert(Planner planner, Transaction tx, String type, int id, int key) {
      planner.executeUpdate("insert into items(id, keyval) values (" + id + ", "
            + literal(type, key) + ")", tx);
   }

   private static void assertState(SimpleDB db, Transaction tx, String type,
                                   Map<Integer, Integer> model) {
      assertQueries(db, tx, type, model, true);
      int buffers = tx.availableBuffs();
      IndexInfo info = db.mdMgr().getIndexInfo("items", tx).get("keyval");
      for (int key = 0; key < 6; key++) {
         Index index = info.open();
         try {
            TableScan table = new TableScan(tx, "items", db.mdMgr().getLayout("items", tx));
            try {
               Map<Integer, Integer> actual = new LinkedHashMap<>();
               index.beforeFirst(value(type, key));
               while (index.next()) {
                  table.moveToRid(index.getDataRid());
                  check(table.getVal("keyval").equals(value(type, key)), "Index RID has wrong key");
                  actual.merge(table.getInt("id"), 1, Integer::sum);
               }
               check(expected(model, key).equals(actual), "Raw index contents differ: " + actual);
            } finally {
               table.close();
            }
         } finally {
            index.close();
         }
         check(tx.availableBuffs() == buffers, "Index probe leaked buffers");
      }
   }

   private static void assertQueries(SimpleDB db, Transaction tx, String type,
                                     Map<Integer, Integer> model, boolean indexed) {
      int buffers = tx.availableBuffs();
      for (int key = 0; key < 6; key++) {
         Plan plan = db.planner().createQueryPlan("select id, keyval from items where keyval = "
               + literal(type, key), tx);
         check(PlanExplainer.explain(plan).contains("IndexSelect") == indexed,
               "Expected indexed=" + indexed + " for key " + key);
         Scan scan = plan.open();
         Map<Integer, Integer> actual = new LinkedHashMap<>();
         try {
            while (scan.next()) {
               check(scan.getVal("keyval").equals(value(type, key)), "SQL returned wrong key");
               actual.merge(scan.getInt("id"), 1, Integer::sum);
            }
         } finally {
            scan.close();
         }
         check(expected(model, key).equals(actual), "SQL result bag differs for " + type
               + " key " + key + ": " + actual);
         check(tx.availableBuffs() == buffers, "SQL probe leaked buffers");
      }
   }

   private static Map<Integer, Integer> expected(Map<Integer, Integer> model, int key) {
      Map<Integer, Integer> result = new LinkedHashMap<>();
      model.forEach((id, storedKey) -> {
         if (storedKey == key) result.put(id, 1);
      });
      return result;
   }

   private static Constant value(String type, int key) {
      if (type.equals("int")) return new Constant(new int[] { -7, 0, 7, 19, 42, 99 }[key]);
      if (type.equals("time")) return Constant.time(new String[] {
            "00:00:00", "09:30:00.123456", "12:00:00", "18:00:00",
            "23:59:59.999999", "01:02:03" }[key]);
      return new Constant(new String[] { "alpha", "beta", "delta", "gamma", "zeta", "absent" }[key]);
   }

   private static String literal(String type, int key) {
      Constant value = value(type, key);
      return type.equals("int") ? value.toString() : "'" + value + "'";
   }

   private static void check(boolean condition, String message) {
      checks++;
      if (!condition) throw new AssertionError(message);
   }
}
