package simpledb.plan;

import static java.sql.Types.INTEGER;
import java.util.*;
import simpledb.metadata.MetadataMgr;
import simpledb.parse.*;
import simpledb.query.*;
import simpledb.record.Schema;
import simpledb.tx.Transaction;

/** Resolves and checks a query against its logical FROM inputs before planning. */
public final class QueryValidator {
   private final MetadataMgr mdm;

   public QueryValidator(MetadataMgr mdm) { this.mdm = mdm; }

   public Schema validate(QueryData data, Transaction tx) {
      return validate(data, tx, new LinkedHashSet<String>());
   }

   private Schema validate(QueryData data, Transaction tx, Set<String> viewStack) {
      if (data.tables().isEmpty())
         throw new IllegalArgumentException("FROM clause must name at least one table or view");
      Schema combined = new Schema();
      Map<String,Integer> occurrences = new HashMap<String,Integer>();
      for (String name : data.tables()) {
         Schema input;
         String definition = mdm.getViewDef(name, tx);
         if (definition != null) {
            if (!viewStack.add(name))
               throw new IllegalArgumentException("View cycle while validating FROM view: " + name);
            try {
               QueryData view = new Parser(definition).query();
               input = validate(view, tx, viewStack);
            } catch (IllegalArgumentException e) {
               throw new IllegalArgumentException("Invalid view " + name + ": " + e.getMessage(), e);
            } finally { viewStack.remove(name); }
         } else {
            try { input = mdm.getLayout(name, tx).schema(); }
            catch (IllegalArgumentException e) {
               throw new IllegalArgumentException("FROM table or view not found: " + name, e);
            }
         }
         for (String field : input.fields()) {
            occurrences.put(field, occurrences.getOrDefault(field, 0) + 1);
            if (!combined.hasField(field)) combined.add(field, input);
         }
      }
      for (SelectItem item : data.selectItems()) {
         if (!item.isAggregate()) {
            resolve(item.fieldName(), "SELECT", combined, occurrences);
            continue;
         }
         if (item.argument() == null) continue; // COUNT(*)
         String arg = item.argument();
         resolve(arg, item.aggregate().name(), combined, occurrences);
         int type = combined.type(arg);
         if ((item.aggregate() == SelectItem.Aggregate.SUM || item.aggregate() == SelectItem.Aggregate.AVG)
               && type != INTEGER)
            throw new IllegalArgumentException(item.aggregate().name() + " requires an integer field: " + arg);
      }
      for (String field : data.groupFields()) resolve(field, "GROUP BY", combined, occurrences);
      for (String field : data.orderFields())
         if (!data.fields().contains(field)) resolve(field, "ORDER BY", combined, occurrences);
      validatePredicate(data.pred(), combined, occurrences);

      boolean grouped = data.hasAggregates() || data.hasGroupBy();
      if (grouped) for (SelectItem item : data.selectItems())
         if (!item.isAggregate() && !data.groupFields().contains(item.fieldName()))
            throw new IllegalArgumentException("SELECT field " + item.fieldName() + " must appear in GROUP BY");
      if (data.isDistinct() && data.hasOrderBy())
         for (String field : data.orderFields())
            if (!data.fields().contains(field))
               throw new IllegalArgumentException("ORDER BY field " + field + " must appear in SELECT with DISTINCT");

      // ORDER BY runs after projection/aggregation in this engine.
      Set<String> available = new HashSet<String>(data.fields());
      for (String field : data.orderFields())
         if (!available.contains(field))
            throw new IllegalArgumentException("ORDER BY field not available in SELECT output: " + field);

      Schema output = new Schema();
      for (SelectItem item : data.selectItems()) {
         String out = item.outputFieldName();
         int type = item.isAggregate()
               ? (item.aggregate() == SelectItem.Aggregate.COUNT ? INTEGER : combined.type(item.argument()))
               : combined.type(item.fieldName());
         if (type == INTEGER) output.addIntField(out);
         else if (type == java.sql.Types.TIME) output.addTimeField(out);
         else output.addStringField(out, combined.length(
               item.isAggregate() ? item.argument() : item.fieldName()));
      }
      return output;
   }

   private void resolve(String field, String clause, Schema schema, Map<String,Integer> counts) {
      Integer count = counts.get(field);
      if (count == null) throw new IllegalArgumentException(clause + " field not found: " + field);
      if (count > 1) throw new IllegalArgumentException(clause + " field is ambiguous: " + field);
   }

   private void validatePredicate(Predicate predicate, Schema schema, Map<String,Integer> counts) {
      for (Term term : predicate.terms()) {
         Expression left = term.lhs(), right = term.rhs();
         if (left.isFieldName()) resolve(left.asFieldName(), "WHERE", schema, counts);
         if (right.isFieldName()) resolve(right.asFieldName(), "WHERE", schema, counts);
         int lt = left.isFieldName() ? schema.type(left.asFieldName()) : left.asConstant().type();
         int rt = right.isFieldName() ? schema.type(right.asFieldName()) : right.asConstant().type();
         if (lt == java.sql.Types.TIME && rt == java.sql.Types.VARCHAR && !right.isFieldName()) {
            try { Constant.time(right.asConstant().asString()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("WHERE has invalid TIME literal: " + right, e); }
         } else if (rt == java.sql.Types.TIME && lt == java.sql.Types.VARCHAR && !left.isFieldName()) {
            try { Constant.time(left.asConstant().asString()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("WHERE has invalid TIME literal: " + left, e); }
         } else if (lt != rt) {
            throw new IllegalArgumentException("WHERE comparison has incompatible types: " + term);
         }
      }
      predicate.validateTimeTypes(schema);
   }
}
