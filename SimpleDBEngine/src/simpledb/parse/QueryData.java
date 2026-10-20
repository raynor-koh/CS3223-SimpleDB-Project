package simpledb.parse;

import java.util.*;
import static java.sql.Types.INTEGER;

import simpledb.query.*;
import simpledb.record.Schema;

/**
 * Data for the SQL <i>select</i> statement.
 * @author Edward Sciore
 */
public class QueryData {
   private boolean distinct;
   private List<SelectItem> selectItems;
   private Collection<String> tables;
   private Predicate pred;
   private List<String> groupFields;
   private List<String> orderFields;
   private List<Boolean> orderDirections;
   
   /**
    * Saves the field and table list and predicate.
    */
   public QueryData(List<String> fields, Collection<String> tables, Predicate pred) {
      this(false, toSelectItems(fields), tables, pred, new ArrayList<String>(),
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
      this(false, toSelectItems(fields), tables, pred, new ArrayList<String>(),
           orderFields, orderDirections);
   }

   /** Creates query data from parsed typed SELECT items. */
   public static QueryData withSelectItems(List<SelectItem> selectItems,
         Collection<String> tables, Predicate pred, List<String> groupFields,
         List<String> orderFields, List<Boolean> orderDirections) {
      return withSelectItems(false, selectItems, tables, pred, groupFields,
            orderFields, orderDirections);
   }

   /** Creates query data from parsed typed SELECT items and DISTINCT state. */
   public static QueryData withSelectItems(boolean distinct,
         List<SelectItem> selectItems, Collection<String> tables,
         Predicate pred, List<String> groupFields, List<String> orderFields,
         List<Boolean> orderDirections) {
      return new QueryData(distinct, selectItems, tables, pred, groupFields,
            orderFields, orderDirections);
   }

   private QueryData(boolean distinct, List<SelectItem> selectItems,
                     Collection<String> tables,
                     Predicate pred, List<String> groupFields,
                     List<String> orderFields,
                     List<Boolean> orderDirections) {
      if (orderFields.size() != orderDirections.size())
         throw new IllegalArgumentException(
               "Each ORDER BY field must have a direction");
      this.distinct = distinct;
      this.selectItems = new ArrayList<SelectItem>(selectItems);
      this.tables = new ArrayList<String>(tables);
      this.pred = pred;
      this.groupFields = new ArrayList<String>(groupFields);
      this.orderFields = new ArrayList<String>(orderFields);
      this.orderDirections = new ArrayList<Boolean>(orderDirections);
   }
   
   /**
    * Returns the fields mentioned in the select clause.
    * @return a list of field names
    */
   public List<String> fields() {
      List<String> fields = new ArrayList<String>();
      for (SelectItem item : selectItems)
         fields.add(item.outputFieldName());
      return fields;
   }

   /** Returns the parsed SELECT items in their SQL order. */
   public List<SelectItem> selectItems() {
      return Collections.unmodifiableList(selectItems);
   }

   /** Returns whether the SELECT clause requests duplicate elimination. */
   public boolean isDistinct() {
      return distinct;
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

   /** Returns the fields that define each group, in sort-key order. */
   public List<String> groupFields() {
      return Collections.unmodifiableList(groupFields);
   }

   public boolean hasGroupBy() {
      return !groupFields.isEmpty();
   }

   public boolean hasAggregates() {
      for (SelectItem item : selectItems)
         if (item.isAggregate())
            return true;
      return false;
   }

   /**
    * Verifies aggregate and grouping semantics against the input relation.
    * This does not construct aggregate operators; it gives planners a clear
    * error before they attempt to project an expression as though it were a
    * physical input field.
    */
   public void validateAgainst(Schema schema) {
      boolean groupedQuery = hasAggregates() || hasGroupBy();
      for (String groupField : groupFields)
         requireField(schema, groupField, "GROUP BY");

      for (SelectItem item : selectItems) {
         if (!item.isAggregate()) {
            requireField(schema, item.fieldName(), "SELECT");
            if (groupedQuery && !groupFields.contains(item.fieldName()))
               throw new IllegalArgumentException("Selected field "
                     + item.fieldName() + " must appear in GROUP BY");
            continue;
         }

         String argument = item.argument();
         if (argument != null) {
            requireField(schema, argument, item.aggregate().name());
            if ((item.aggregate() == SelectItem.Aggregate.SUM
                  || item.aggregate() == SelectItem.Aggregate.AVG)
                  && schema.type(argument) != INTEGER)
               throw new IllegalArgumentException(item.aggregate().name()
                     + " requires an integer field: " + argument);
         }
      }
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
      String result = distinct ? "select distinct " : "select ";
      for (SelectItem item : selectItems)
         result += item + ", ";
      result = result.substring(0, result.length()-2); //remove final comma
      result += " from ";
      for (String tblname : tables)
         result += tblname + ", ";
      result = result.substring(0, result.length()-2); //remove final comma
      String predstring = pred.toString();
      if (!predstring.equals(""))
         result += " where " + predstring;
      if (hasGroupBy()) {
         result += " group by ";
         for (String fldname : groupFields)
            result += fldname + ", ";
         result = result.substring(0, result.length()-2);
      }
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

   private static List<SelectItem> toSelectItems(List<String> fields) {
      List<SelectItem> result = new ArrayList<SelectItem>();
      for (String field : fields)
         result.add(new SelectItem(field));
      return result;
   }

   private static void requireField(Schema schema, String field,
                                    String clause) {
      if (!schema.hasField(field))
         throw new IllegalArgumentException(clause + " field not found: "
               + field);
   }
}
