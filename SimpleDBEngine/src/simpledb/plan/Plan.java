package simpledb.plan;

import java.util.Collections;
import java.util.List;

import simpledb.query.Scan;
import simpledb.record.*;

/**
 * The interface implemented by each query plan.
 * There is a Plan class for each relational algebra operator.
 * @author Edward Sciore
 *
 */
public interface Plan {
   
   /**
    * Opens a scan corresponding to this plan.
    * The scan will be positioned before its first record.
    * @return a scan
    */
   public Scan   open();
   
   /**
    * Returns an estimate of the number of block accesses
    * that will occur when the scan is read to completion.
    * @return the estimated number of block accesses
    */
   public int    blocksAccessed();
   
   /**
    * Returns an estimate of the number of records
    * in the query's output table.
    * @return the estimated number of output records
    */
   public int    recordsOutput();
   
   /**
    * Returns an estimate of the number of distinct values
    * for the specified field in the query's output table.
    * @param fldname the name of a field
    * @return the estimated number of distinct field values in the output
    */
   public int    distinctValues(String fldname);
   
   /**
    * Returns the schema of the query.
    * @return the query's schema
    */
   public Schema schema();

   /**
    * Returns a concise, deterministic description of this physical operator.
    * It is used for plan display and must not open a scan.
    */
   public default String operationDescription() {
      return getClass().getSimpleName();
   }

   /**
    * Returns this operator's physical input plans in display order.
    * It is used for plan display and must not open a scan.
    */
   public default List<Plan> children() {
      return Collections.emptyList();
   }
}
