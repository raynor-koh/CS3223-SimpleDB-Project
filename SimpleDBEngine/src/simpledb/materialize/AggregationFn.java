package simpledb.materialize;

import simpledb.query.*;
import simpledb.record.Schema;

/**
 * The interface implemented by aggregation functions.
 * Aggregation functions are used by the <i>groupby</i> operator.
 * @author Edward Sciore
 */
public interface AggregationFn {
   
   /**
    * Use the current record of the specified scan
    * to be the first record in the group.
    * @param s the scan to aggregate over.
    */
   void processFirst(Scan s);
   
   /**
    * Use the current record of the specified scan
    * to be the next record in the group.
    * @param s the scan to aggregate over.
    */
   void processNext(Scan s);
   
   /**
    * Return the name of the new aggregation field.
    * @return the name of the new aggregation field
    */
   String fieldName();

   /**
    * Adds this aggregate's result field to an output schema. Implementations
    * that preserve their argument's type (MIN and MAX) use the input schema;
    * numeric aggregates add an integer field.
    */
   void addFieldTo(Schema outputSchema, Schema inputSchema);
   
   /**
    * Return the computed aggregation value.
    * @return the computed aggregation value
    */
   Constant value();
}
