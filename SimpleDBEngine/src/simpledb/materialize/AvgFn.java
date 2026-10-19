package simpledb.materialize;

import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.Schema;

/** The integer AVG aggregation function. Division truncates toward zero. */
public class AvgFn implements AggregationFn {
   private String fldname;
   private long sum;
   private long count;

   public AvgFn(String fldname) {
      this.fldname = fldname;
   }

   public void processFirst(Scan s) {
      sum = s.getVal(fldname).asInt();
      count = 1;
   }

   public void processNext(Scan s) {
      sum = Math.addExact(sum, s.getVal(fldname).asInt());
      count = Math.addExact(count, 1);
   }

   public String fieldName() {
      return "avgof" + fldname;
   }

   public void addFieldTo(Schema outputSchema, Schema inputSchema) {
      if (inputSchema.type(fldname) != java.sql.Types.INTEGER)
         throw new IllegalArgumentException("AVG requires an integer field: "
               + fldname);
      outputSchema.addIntField(fieldName());
   }

   public Constant value() {
      return new Constant(Math.toIntExact(sum / count));
   }
}
