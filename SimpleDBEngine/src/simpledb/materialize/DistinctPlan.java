package simpledb.materialize;

import java.util.ArrayList;
import java.util.List;

import simpledb.plan.Plan;
import simpledb.query.Scan;
import simpledb.record.Schema;
import simpledb.tx.Transaction;

/**
 * The plan class for duplicate elimination using an external sort.
 */
public class DistinctPlan implements Plan {
   private Plan p;
   private SortPlan sorted;
   private List<String> fields;

   /**
    * Creates a duplicate-elimination plan for every field in the input tuple.
    *
    * @param tx the calling transaction
    * @param p the input plan
    */
   public DistinctPlan(Transaction tx, Plan p) {
      this.p = p;
      fields = new ArrayList<String>(p.schema().fields());
      sorted = new SortPlan(tx, p, fields);
   }

   /** Opens a sorted scan and removes adjacent equal tuples from it. */
   public Scan open() {
      return new DistinctScan(sorted.open(), fields);
   }

   /**
    * Returns the cost estimate of the underlying sort operation.
    */
   public int blocksAccessed() {
      return sorted.blocksAccessed();
   }

   /**
    * Returns a safe upper bound until a more precise multi-column estimate is
    * available.
    */
   public int recordsOutput() {
      return p.recordsOutput();
   }

   /**
    * Duplicate elimination cannot increase the number of distinct values.
    */
   public int distinctValues(String fldname) {
      return Math.min(p.distinctValues(fldname), recordsOutput());
   }

   /** Returns the input schema unchanged. */
   public Schema schema() {
      return p.schema();
   }
}
