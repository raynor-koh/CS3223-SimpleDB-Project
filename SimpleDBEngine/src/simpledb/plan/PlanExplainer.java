package simpledb.plan;

/** Formats a physical query plan as a deterministic, non-executing tree. */
public final class PlanExplainer {
   private PlanExplainer() {
   }

   /**
    * Returns a pre-order plan tree with per-operator cost estimates.
    * This method never calls {@link Plan#open()}.
    */
   public static String explain(Plan root) {
      if (root == null)
         throw new IllegalArgumentException("Plan root cannot be null");
      StringBuilder result = new StringBuilder();
      append(root, 0, result);
      return result.toString();
   }

   private static void append(Plan plan, int depth, StringBuilder result) {
      if (result.length() > 0)
         result.append(System.lineSeparator());
      for (int i = 0; i < depth; i++)
         result.append("  ");
      result.append(plan.operationDescription());
      result.append(" [blocks=").append(plan.blocksAccessed());
      result.append(", records=").append(plan.recordsOutput()).append("]");
      for (Plan child : plan.children())
         append(child, depth + 1, result);
   }
}
