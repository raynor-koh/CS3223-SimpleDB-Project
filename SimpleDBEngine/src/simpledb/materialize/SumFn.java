package simpledb.materialize;

import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.Schema;

/** The integer SUM aggregation function. */
public class SumFn implements AggregationFn {
   private String fldname;
   private long sum;

   public SumFn(String fldname) {
      this.fldname = fldname;
   }

   public void processFirst(Scan s) {
      sum = s.getVal(fldname).asInt();
   }

   public void processNext(Scan s) {
      sum = Math.addExact(sum, s.getVal(fldname).asInt());
   }

   public String fieldName() {
      return "sumof" + fldname;
   }

   public void addFieldTo(Schema outputSchema, Schema inputSchema) {
      requireIntegerInput(inputSchema);
      outputSchema.addIntField(fieldName());
   }

   public Constant value() {
      return new Constant(Math.toIntExact(sum));
   }

   private void requireIntegerInput(Schema inputSchema) {
      if (inputSchema.type(fldname) != java.sql.Types.INTEGER)
         throw new IllegalArgumentException("SUM requires an integer field: "
               + fldname);
   }
}
