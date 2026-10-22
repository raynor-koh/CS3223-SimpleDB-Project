package simpledb.parse;

/**
 * One item in a SELECT list. An item is either a plain field reference or an
 * aggregate expression over a field (or over all rows for COUNT(*)).
 */
public final class SelectItem {
   /** The aggregate functions understood by the SQL front end. */
   public enum Aggregate {
      COUNT, SUM, AVG, MIN, MAX
   }

   private final String field;
   private final Aggregate aggregate;
   private final String argument;

   /** Creates a plain field item. */
   public SelectItem(String field) {
      if (field == null)
         throw new IllegalArgumentException("Field name cannot be null");
      this.field = field;
      this.aggregate = null;
      this.argument = null;
   }

   /**
    * Creates an aggregate item. A null argument represents COUNT(*), and is
    * valid only for COUNT.
    */
   public SelectItem(Aggregate aggregate, String argument) {
      if (aggregate == null)
         throw new IllegalArgumentException("Aggregate cannot be null");
      if (argument == null && aggregate != Aggregate.COUNT)
         throw new IllegalArgumentException("Only COUNT may use *");
      this.field = null;
      this.aggregate = aggregate;
      this.argument = argument;
   }

   public boolean isAggregate() {
      return aggregate != null;
   }

   public String fieldName() {
      if (isAggregate())
         throw new IllegalStateException("Aggregate item has no plain field");
      return field;
   }

   public Aggregate aggregate() {
      if (!isAggregate())
         throw new IllegalStateException("Field item has no aggregate");
      return aggregate;
   }

   /** Returns the aggregate input field, or null for COUNT(*). */
   public String argument() {
      if (!isAggregate())
         throw new IllegalStateException("Field item has no aggregate argument");
      return argument;
   }

   /** Returns the stable output name used by later aggregate operators. */
   public String outputFieldName() {
      if (!isAggregate())
         return field;
      String suffix = argument == null ? "all" : argument;
      return aggregate.name().toLowerCase() + "of" + suffix;
   }

   @Override
   public String toString() {
      if (!isAggregate())
         return field;
      return aggregate.name().toLowerCase() + "("
            + (argument == null ? "*" : argument) + ")";
   }
}
