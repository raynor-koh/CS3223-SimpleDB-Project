package simpledb.multibuffer;

import java.util.ArrayList;
import java.util.List;

import simpledb.materialize.TempTable;
import simpledb.plan.Plan;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.query.UpdateScan;
import simpledb.record.Schema;
import simpledb.record.RID;
import simpledb.tx.Transaction;

/** Copies an input into temporary tables selected by a hash-key partition. */
public final class HashPartitioner {
   private HashPartitioner() {
   }

   public static List<TempTable> partition(Transaction tx, Plan input,
         String keyField, int partitions, int seed) {
      Scan scan = input.open();
      try {
         return partition(tx, scan, input.schema(), keyField, partitions, seed);
      }
      finally {
         scan.close();
      }
   }

   public static List<TempTable> partition(Transaction tx, TempTable input,
         String keyField, int partitions, int seed) {
      Scan scan = input.open();
      try {
         return partition(tx, scan, input.getLayout().schema(), keyField,
               partitions, seed);
      }
      finally {
         scan.close();
      }
   }

   private static List<TempTable> partition(Transaction tx, Scan source,
         Schema schema, String keyField, int partitions, int seed) {
      if (partitions < 1)
         throw new IllegalArgumentException("At least one hash partition is required");
      if (!schema.hasField(keyField))
         throw new IllegalArgumentException("Hash key field not found: " + keyField);

      List<TempTable> tables = new ArrayList<TempTable>();
      RID[] lastRecords = new RID[partitions];
      for (int i = 0; i < partitions; i++) {
         TempTable table = new TempTable(tx, schema);
         tables.add(table);
         // Materialize empty partitions without keeping all of them pinned.
         // Indexed and joined sources can hold several buffers themselves.
         table.open().close();
      }

      while (source.next()) {
         Constant key = source.getVal(keyField);
         int bucket = Math.floorMod(key.hashCode() ^ seed, partitions);
         UpdateScan destination = tables.get(bucket).open();
         try {
            // Resume appending instead of searching from the first block each time.
            if (lastRecords[bucket] != null)
               destination.moveToRid(lastRecords[bucket]);
            destination.insert();
            for (String field : schema.fields())
               destination.setVal(field, source.getVal(field));
            lastRecords[bucket] = destination.getRid();
         } finally {
            // Release the output before advancing a source that may need to
            // open another index page or join partition on its next call.
            destination.close();
         }
      }
      return tables;
   }
}
