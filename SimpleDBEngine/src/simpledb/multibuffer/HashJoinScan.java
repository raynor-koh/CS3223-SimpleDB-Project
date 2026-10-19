package simpledb.multibuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import simpledb.materialize.TempTable;
import simpledb.plan.Plan;
import simpledb.plan.NestedLoopJoinPlan;
import simpledb.query.Constant;
import simpledb.query.Expression;
import simpledb.query.Predicate;
import simpledb.query.Scan;
import simpledb.query.Term;
import simpledb.record.Schema;
import simpledb.tx.Transaction;

/**
 * Scan implementation for a Grace-style hash join. Inputs are partitioned to
 * temporary tables, then matching partition pairs are hash-probed. Oversized
 * or persistently skewed pairs use the existing nested-loop implementation as
 * a bounded correctness fallback.
 */
public class HashJoinScan implements Scan {
   private static final int MAX_REPARTITION_DEPTH = 2;

   private Transaction tx;
   private Plan lhs;
   private Plan rhs;
   private Schema lhsSchema;
   private Schema rhsSchema;
   private String lhsKey;
   private String rhsKey;
   private Deque<PartitionPair> pendingPairs;
   private Map<Constant, List<TupleSnapshot>> hashTable;
   private Scan probeScan;
   private Scan fallbackScan;
   private Iterator<TupleSnapshot> matches;
   private TupleSnapshot currentBuild;
   private boolean buildIsLeft;
   private boolean closed;

   public HashJoinScan(Transaction tx, Plan lhs, Plan rhs, String lhsKey,
                       String rhsKey) {
      this.tx = tx;
      this.lhs = lhs;
      this.rhs = rhs;
      this.lhsSchema = lhs.schema();
      this.rhsSchema = rhs.schema();
      this.lhsKey = lhsKey;
      this.rhsKey = rhsKey;
      beforeFirst();
   }

   public void beforeFirst() {
      closeCurrentWork();
      closed = false;
      pendingPairs = new ArrayDeque<PartitionPair>();
      int partitions = partitionCount();
      List<TempTable> leftParts = HashPartitioner.partition(tx, lhs, lhsKey,
            partitions, 0);
      List<TempTable> rightParts = HashPartitioner.partition(tx, rhs, rhsKey,
            partitions, 0);
      for (int i = 0; i < partitions; i++)
         pendingPairs.addLast(new PartitionPair(leftParts.get(i), rightParts.get(i),
               0, 0));
   }

   public boolean next() {
      if (closed)
         return false;
      while (true) {
         if (fallbackScan != null) {
            if (fallbackScan.next())
               return true;
            fallbackScan.close();
            fallbackScan = null;
         }

         if (matches != null && matches.hasNext()) {
            currentBuild = matches.next();
            return true;
         }
         matches = null;

         if (probeScan != null) {
            while (probeScan.next()) {
               Constant key = probeScan.getVal(buildIsLeft ? rhsKey : lhsKey);
               List<TupleSnapshot> candidates = hashTable.get(key);
               if (candidates != null && !candidates.isEmpty()) {
                  matches = candidates.iterator();
                  currentBuild = matches.next();
                  return true;
               }
            }
            closeProbeWork();
         }

         if (!startNextPair())
            return false;
      }
   }

   public int getInt(String field) {
      return getVal(field).asInt();
   }

   public String getString(String field) {
      return getVal(field).asString();
   }

   public Constant getVal(String field) {
      if (fallbackScan != null)
         return fallbackScan.getVal(field);
      if (currentBuild == null || probeScan == null)
         throw new IllegalStateException("Hash join scan is not positioned on a record");
      boolean leftField = lhsSchema.hasField(field);
      if (buildIsLeft == leftField)
         return currentBuild.get(field);
      return probeScan.getVal(field);
   }

   public boolean hasField(String field) {
      return lhsSchema.hasField(field) || rhsSchema.hasField(field);
   }

   public void close() {
      closeCurrentWork();
      if (pendingPairs != null)
         pendingPairs.clear();
      closed = true;
   }

   private boolean startNextPair() {
      while (!pendingPairs.isEmpty()) {
         PartitionPair pair = pendingPairs.removeFirst();
         int leftBlocks = blocks(pair.left);
         int rightBlocks = blocks(pair.right);
         boolean leftBuild = leftBlocks <= rightBlocks;
         int buildBlocks = leftBuild ? leftBlocks : rightBlocks;

         if (buildBlocks > buildBlockLimit()
               && pair.depth < MAX_REPARTITION_DEPTH
               && partitionCount() > 1) {
            enqueueRepartitionedPairs(pair);
            continue;
         }
         if (buildBlocks > buildBlockLimit()) {
            fallbackScan = nestedLoopPair(pair);
            continue;
         }

         buildIsLeft = leftBuild;
         TempTable build = leftBuild ? pair.left : pair.right;
         TempTable probe = leftBuild ? pair.right : pair.left;
         String buildKey = leftBuild ? lhsKey : rhsKey;
         hashTable = loadHashTable(build, buildKey);
         probeScan = probe.open();
         currentBuild = null;
         return true;
      }
      return false;
   }

   private void enqueueRepartitionedPairs(PartitionPair pair) {
      int partitions = partitionCount();
      int nextSeed = pair.seed + 0x9e3779b9;
      List<TempTable> leftParts = HashPartitioner.partition(tx, pair.left, lhsKey,
            partitions, nextSeed);
      List<TempTable> rightParts = HashPartitioner.partition(tx, pair.right,
            rhsKey, partitions, nextSeed);
      for (int i = 0; i < partitions; i++)
         pendingPairs.addLast(new PartitionPair(leftParts.get(i), rightParts.get(i),
               pair.depth + 1, nextSeed));
   }

   private Map<Constant, List<TupleSnapshot>> loadHashTable(TempTable table,
                                                              String key) {
      Map<Constant, List<TupleSnapshot>> result =
            new HashMap<Constant, List<TupleSnapshot>>();
      Scan scan = table.open();
      try {
         Schema schema = table.getLayout().schema();
         while (scan.next()) {
            Constant value = scan.getVal(key);
            List<TupleSnapshot> bucket = result.get(value);
            if (bucket == null) {
               bucket = new ArrayList<TupleSnapshot>();
               result.put(value, bucket);
            }
            bucket.add(new TupleSnapshot(scan, schema));
         }
      }
      finally {
         scan.close();
      }
      return result;
   }

   private Scan nestedLoopPair(PartitionPair pair) {
      Plan left = new TempTablePlan(pair.left);
      Plan right = new TempTablePlan(pair.right);
      Predicate equality = new Predicate(new Term(new Expression(lhsKey),
            new Expression(rhsKey)));
      return new NestedLoopJoinPlan(left, right, equality).open();
   }

   private int partitionCount() {
      return Math.max(1, tx.availableBuffs() - 1);
   }

   private int buildBlockLimit() {
      return Math.max(1, tx.availableBuffs() - 2);
   }

   private int blocks(TempTable table) {
      return tx.size(table.tableName() + ".tbl");
   }

   private void closeCurrentWork() {
      if (fallbackScan != null) {
         fallbackScan.close();
         fallbackScan = null;
      }
      closeProbeWork();
      currentBuild = null;
   }

   private void closeProbeWork() {
      if (probeScan != null) {
         probeScan.close();
         probeScan = null;
      }
      hashTable = null;
      matches = null;
   }

   private static class PartitionPair {
      private TempTable left;
      private TempTable right;
      private int depth;
      private int seed;

      PartitionPair(TempTable left, TempTable right, int depth, int seed) {
         this.left = left;
         this.right = right;
         this.depth = depth;
         this.seed = seed;
      }
   }

   private static class TupleSnapshot {
      private Map<String, Constant> values = new HashMap<String, Constant>();

      TupleSnapshot(Scan scan, Schema schema) {
         for (String field : schema.fields())
            values.put(field, scan.getVal(field));
      }

      Constant get(String field) {
         return values.get(field);
      }
   }

   /** A minimal Plan wrapper used only for the per-partition fallback scan. */
   private static class TempTablePlan implements Plan {
      private TempTable table;

      TempTablePlan(TempTable table) {
         this.table = table;
      }

      public Scan open() { return table.open(); }
      public int blocksAccessed() { return 0; }
      public int recordsOutput() { return 0; }
      public int distinctValues(String field) { return 0; }
      public Schema schema() { return table.getLayout().schema(); }
   }
}
