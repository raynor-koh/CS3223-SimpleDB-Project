package simpledb.materialize;

import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.Schema;

/** The MIN aggregation function. */
public class MinFn implements AggregationFn {
   private String fldname;
   private Constant val;

   public MinFn(String fldname) {
      this.fldname = fldname;
   }

   public void processFirst(Scan s) {
      val = s.getVal(fldname);
   }

   public void processNext(Scan s) {
      Constant newval = s.getVal(fldname);
      if (newval.compareTo(val) < 0)
         val = newval;
   }

   public String fieldName() {
      return "minof" + fldname;
   }

   public void addFieldTo(Schema outputSchema, Schema inputSchema) {
      outputSchema.addField(fieldName(), inputSchema.type(fldname),
            inputSchema.length(fldname));
   }

   public Constant value() {
      return val;
   }
}
