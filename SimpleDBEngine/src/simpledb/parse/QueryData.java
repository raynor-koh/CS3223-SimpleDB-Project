package simpledb.parse;

import java.util.*;

import simpledb.query.*;

/**
 * Data for the SQL <i>select</i> statement.
 * @author Edward Sciore
 */
public class QueryData {
   private List<String> fields;
   private Collection<String> tables;
   private Predicate pred;
   private List<String> orderFields;
   private List<Boolean> orderDirections;
   
   /**
    * Saves the field and table list and predicate.
    */
   public QueryData(List<String> fields, Collection<String> tables, Predicate pred) {
      this(fields, tables, pred,
           new ArrayList<String>(), new ArrayList<Boolean>());
   }

   /**
    * Saves the field and table lists, predicate, and ORDER BY details.
    * Each entry in orderDirections corresponds to the field at the same
    * position in orderFields; true means ascending and false descending.
    */
   public QueryData(List<String> fields, Collection<String> tables,
                    Predicate pred, List<String> orderFields,
                    List<Boolean> orderDirections) {
      if (orderFields.size() != orderDirections.size())
         throw new IllegalArgumentException(
               "Each ORDER BY field must have a direction");
      this.fields = fields;
      this.tables = tables;
      this.pred = pred;
      this.orderFields = new ArrayList<String>(orderFields);
      this.orderDirections = new ArrayList<Boolean>(orderDirections);
   }
   
   /**
    * Returns the fields mentioned in the select clause.
    * @return a list of field names
    */
   public List<String> fields() {
      return fields;
   }
   
   /**
    * Returns the tables mentioned in the from clause.
    * @return a collection of table names
    */
   public Collection<String> tables() {
      return tables;
   }
   
   /**
    * Returns the predicate that describes which
    * records should be in the output table.
    * @return the query predicate
    */
   public Predicate pred() {
      return pred;
   }

   /**
    * Returns the fields in the ORDER BY clause, in comparison order.
    * @return a list of sort field names
    */
   public List<String> orderFields() {
      return Collections.unmodifiableList(orderFields);
   }

   /**
    * Returns the direction of each ORDER BY field. True means ascending.
    * @return a list of sort directions
    */
   public List<Boolean> orderDirections() {
      return Collections.unmodifiableList(orderDirections);
   }

   /**
    * Returns true if this query contains an ORDER BY clause.
    * @return whether sorting was requested
    */
   public boolean hasOrderBy() {
      return !orderFields.isEmpty();
   }
   
   public String toString() {
      String result = "select ";
      for (String fldname : fields)
         result += fldname + ", ";
      result = result.substring(0, result.length()-2); //remove final comma
      result += " from ";
      for (String tblname : tables)
         result += tblname + ", ";
      result = result.substring(0, result.length()-2); //remove final comma
      String predstring = pred.toString();
      if (!predstring.equals(""))
         result += " where " + predstring;
      if (hasOrderBy()) {
         result += " order by ";
         for (int i = 0; i < orderFields.size(); i++) {
            result += orderFields.get(i);
            result += orderDirections.get(i) ? " asc" : " desc";
            result += ", ";
         }
         result = result.substring(0, result.length()-2);
      }
      return result;
   }
}
