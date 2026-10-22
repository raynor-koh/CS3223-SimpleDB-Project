package simpledb.materialize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import simpledb.query.Constant;
import simpledb.query.Scan;
import simpledb.record.Schema;

/** AVG over integer inputs, returning DECIMAL(16,6) rounded HALF_UP. */
public class AvgFn implements AggregationFn {
   private String fldname;
   private BigDecimal sum;
   private long count;

   public AvgFn(String fldname) {
      this.fldname = fldname;
   }

   public void processFirst(Scan s) {
      sum = BigDecimal.valueOf(s.getVal(fldname).asInt());
      count = 1;
   }

   public void processNext(Scan s) {
      sum = sum.add(BigDecimal.valueOf(s.getVal(fldname).asInt()));
      count = Math.addExact(count, 1);
   }

   public String fieldName() {
      return "avgof" + fldname;
   }

   public void addFieldTo(Schema outputSchema, Schema inputSchema) {
      if (inputSchema.type(fldname) != java.sql.Types.INTEGER)
         throw new IllegalArgumentException("AVG requires an integer field: "
               + fldname);
      outputSchema.addDecimalField(fieldName());
   }

   public Constant value() {
      return Constant.fromDecimal(sum.divide(BigDecimal.valueOf(count),
            Constant.DECIMAL_SCALE, RoundingMode.HALF_UP));
   }
}
