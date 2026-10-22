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

   /** Whether this function can represent a group with no input records. */
   default boolean supportsEmptyGroup() {
      return false;
   }

   /** Initialize an empty group without reading a nonexistent input record. */
   default void processEmptyGroup() {
      throw new UnsupportedOperationException("Empty group is not supported: " + fieldName());
   }
   
   /**
    * Return the name of the new aggregation field.
    * @return the name of the new aggregation field
    */
   String fieldName();

   /**
    * Adds this aggregate's result field to an output schema. Implementations
    * that preserve their argument's type (MIN and MAX) use the input schema;
    * COUNT/SUM add an integer field and AVG adds a decimal field.
    */
   void addFieldTo(Schema outputSchema, Schema inputSchema);
   
   /**
    * Return the computed aggregation value.
    * @return the computed aggregation value
    */
   Constant value();
}
