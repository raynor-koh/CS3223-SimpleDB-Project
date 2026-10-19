package simpledb.materialize;

import java.util.ArrayList;
import java.util.List;

import simpledb.query.Constant;
import simpledb.query.Scan;

/**
 * A scan that removes adjacent duplicate records from a sorted input scan.
 */
public class DistinctScan implements Scan {
   private Scan s;
   private List<String> fields;
   private List<Constant> previous;

   /**
    * Creates a distinct scan over an input sorted on every specified field.
    *
    * @param s the sorted input scan
    * @param fields the fields that define a duplicate record
    */
   public DistinctScan(Scan s, List<String> fields) {
      this.s = s;
      this.fields = new ArrayList<String>(fields);
      beforeFirst();
   }

   /** Positions the scan before its first distinct record. */
   public void beforeFirst() {
      s.beforeFirst();
      previous = null;
   }

   /**
    * Advances until it finds a record different from the last returned record.
    */
   public boolean next() {
      while (s.next()) {
         if (previous == null || !sameAsPrevious()) {
            previous = currentValues();
            return true;
         }
      }
      return false;
   }

   public void close() {
      s.close();
   }

   public int getInt(String fldname) {
      return s.getInt(fldname);
   }

   public String getString(String fldname) {
      return s.getString(fldname);
   }

   public Constant getVal(String fldname) {
      return s.getVal(fldname);
   }

   public boolean hasField(String fldname) {
      return s.hasField(fldname);
   }

   private boolean sameAsPrevious() {
      for (int i = 0; i < fields.size(); i++)
         if (!previous.get(i).equals(s.getVal(fields.get(i))))
            return false;
      return true;
   }

   private List<Constant> currentValues() {
      List<Constant> values = new ArrayList<Constant>();
      for (String field : fields)
         values.add(s.getVal(field));
      return values;
   }
}
