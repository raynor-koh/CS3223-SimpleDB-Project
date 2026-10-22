package simpledb.plan;

import java.util.ArrayList;
import java.util.List;

import simpledb.materialize.AggregationFn;
import simpledb.materialize.AvgFn;
import simpledb.materialize.CountFn;
import simpledb.materialize.GroupByPlan;
import simpledb.materialize.MaxFn;
import simpledb.materialize.MinFn;
import simpledb.materialize.SumFn;
import simpledb.parse.QueryData;
import simpledb.parse.SelectItem;
import simpledb.tx.Transaction;

/** Shared aggregate/group-by planning logic for query planners. */
public final class AggregateQueryPlannerSupport {
   private AggregateQueryPlannerSupport() {
   }

   /**
    * Validates the SELECT/GROUP BY semantics and adds a sort-based GroupByPlan
    * only when the query requests grouping or aggregation.
    */
   public static Plan apply(Transaction tx, Plan input, QueryData data) {
      data.validateAgainst(input.schema());
      if (!data.hasAggregates() && !data.hasGroupBy())
         return input;
      return new GroupByPlan(tx, input, data.groupFields(), functions(data));
   }

   private static List<AggregationFn> functions(QueryData data) {
      List<AggregationFn> result = new ArrayList<AggregationFn>();
      for (SelectItem item : data.selectItems()) {
         if (!item.isAggregate())
            continue;
         switch (item.aggregate()) {
         case COUNT:
            result.add(new CountFn(item.argument()));
            break;
         case SUM:
            result.add(new SumFn(item.argument()));
            break;
         case AVG:
            result.add(new AvgFn(item.argument()));
            break;
         case MIN:
            result.add(new MinFn(item.argument()));
            break;
         case MAX:
            result.add(new MaxFn(item.argument()));
            break;
         default:
            throw new IllegalArgumentException(
                  "Unsupported aggregate: " + item.aggregate());
         }
      }
      return result;
   }
}
