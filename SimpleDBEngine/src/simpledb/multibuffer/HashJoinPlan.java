package simpledb.multibuffer;

import java.util.Arrays;
import java.util.List;

import simpledb.plan.Plan;
import simpledb.query.Scan;
import simpledb.record.Schema;
import simpledb.tx.Transaction;

/** A partitioned hash join over one equality key from each input. */
public class HashJoinPlan implements Plan {
   private Transaction tx;
   private Plan lhs;
   private Plan rhs;
   private String lhsKey;
   private String rhsKey;
   private Schema schema = new Schema();

   public HashJoinPlan(Transaction tx, Plan lhs, Plan rhs, String lhsKey,
                       String rhsKey) {
      if (!lhs.schema().hasField(lhsKey) || !rhs.schema().hasField(rhsKey))
         throw new IllegalArgumentException("Hash join key is not in its input schema");
      if (lhs.schema().type(lhsKey) != rhs.schema().type(rhsKey))
         throw new IllegalArgumentException("Hash join keys must have matching types");
      for (String field : lhs.schema().fields())
         if (rhs.schema().hasField(field))
            throw new IllegalArgumentException(
                  "Hash join requires distinct left and right field names: " + field);

      this.tx = tx;
      this.lhs = lhs;
      this.rhs = rhs;
      this.lhsKey = lhsKey;
      this.rhsKey = rhsKey;
      schema.addAll(lhs.schema());
      schema.addAll(rhs.schema());
   }

   public Scan open() {
      return new HashJoinScan(tx, lhs, rhs, lhsKey, rhsKey);
   }

   public int blocksAccessed() {
      long inputBlocks = (long) lhs.blocksAccessed() + rhs.blocksAccessed();
      return clamp(3L * inputBlocks);
   }

   public int recordsOutput() {
      int maxDistinct = Math.max(lhs.distinctValues(lhsKey),
            rhs.distinctValues(rhsKey));
      if (maxDistinct == 0)
         return 0;
      return clamp(((long) lhs.recordsOutput() * rhs.recordsOutput())
            / maxDistinct);
   }

   public int distinctValues(String field) {
      if (field.equals(lhsKey) || field.equals(rhsKey))
         return Math.min(lhs.distinctValues(lhsKey), rhs.distinctValues(rhsKey));
      if (lhs.schema().hasField(field))
         return lhs.distinctValues(field);
      return rhs.distinctValues(field);
   }

   public Schema schema() {
      return schema;
   }

   public String operationDescription() {
      return "HashJoin(leftKey=" + lhsKey + ", rightKey=" + rhsKey + ")";
   }

   public List<Plan> children() {
      return Arrays.asList(lhs, rhs);
   }

   private static int clamp(long value) {
      return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
   }
}
