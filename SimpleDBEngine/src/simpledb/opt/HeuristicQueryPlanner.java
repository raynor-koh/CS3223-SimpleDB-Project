package simpledb.opt;

import java.util.*;
import simpledb.tx.Transaction;
import simpledb.materialize.DistinctPlan;
import simpledb.materialize.SortPlan;
import simpledb.metadata.MetadataMgr;
import simpledb.parse.QueryData;
import simpledb.plan.*;

/**
 * A query planner that optimizes using a heuristic-based algorithm.
 * 
 * @author Edward Sciore
 */
public class HeuristicQueryPlanner implements QueryPlanner {
    private Collection<TablePlanner> tableplanners = new ArrayList<>();
    private MetadataMgr mdm;
    private final JoinPolicy joinPolicy;

    public HeuristicQueryPlanner(MetadataMgr mdm) {
        this(mdm, JoinPolicy.defaultPolicy());
    }

    /** Configures eligible join algorithms without changing their preference order. */
    public HeuristicQueryPlanner(MetadataMgr mdm, JoinPolicy joinPolicy) {
        this.mdm = mdm;
        this.joinPolicy = Objects.requireNonNull(joinPolicy, "joinPolicy");
    }

    /**
     * Creates an optimized left-deep query plan using the following
     * heuristics.
     * H1. Choose the smallest table (considering selection predicates)
     * to be first in the join order.
     * H2. Add the table to the join order which
     * results in the smallest output.
     */
    public Plan createPlan(QueryData data, Transaction tx) {
        tableplanners.clear();

        // Step 1: Create a TablePlanner object for each mentioned table
        for (String tblname : data.tables()) {
            TablePlanner tp = new TablePlanner(tblname, data.pred(), tx, mdm, joinPolicy);
            tableplanners.add(tp);
        }

        // Step 2: Choose the lowest-size plan to begin the join order
        Plan currentplan = getLowestSelectPlan();

        // Step 3: Repeatedly add a plan to the join order
        while (!tableplanners.isEmpty()) {
            Plan p = getLowestJoinPlan(currentplan);
            if (p != null)
                currentplan = p;
            else // no applicable join
                currentplan = getLowestProductPlan(currentplan);
        }

        // Step 4. Group and aggregate before projecting result fields.
        currentplan = AggregateQueryPlannerSupport.apply(tx, currentplan, data);

        // Step 5. Project on the field names
        Plan result = new ProjectPlan(currentplan, data.fields());

        // Step 6. Eliminate duplicate projected rows when requested.
        if (data.isDistinct())
            result = new DistinctPlan(tx, result);

        // Step 7. Sort only when the query has an ORDER BY clause
        if (data.hasOrderBy())
            result = new SortPlan(tx, result, data.orderFields(),
                    data.orderDirections());
        return result;
    }

    private Plan getLowestSelectPlan() {
        TablePlanner besttp = null;
        Plan bestplan = null;
        for (TablePlanner tp : tableplanners) {
            Plan plan = tp.makeSelectPlan();
            if (bestplan == null || plan.recordsOutput() < bestplan.recordsOutput()) {
                besttp = tp;
                bestplan = plan;
            }
        }
        tableplanners.remove(besttp);
        return bestplan;
    }

    private Plan getLowestJoinPlan(Plan current) {
        TablePlanner besttp = null;
        Plan bestplan = null;
        for (TablePlanner tp : tableplanners) {
            Plan plan = tp.makeJoinPlan(current);
            if (plan != null && (bestplan == null || plan.recordsOutput() < bestplan.recordsOutput())) {
                besttp = tp;
                bestplan = plan;
            }
        }
        if (bestplan != null)
            tableplanners.remove(besttp);
        return bestplan;
    }

    private Plan getLowestProductPlan(Plan current) {
        // A null join candidate may mean the policy excludes every eligible
        // method, rather than that this is a genuinely disconnected product.
        for (TablePlanner tp : tableplanners) {
            if (tp.hasJoinPredicate(current))
                throw new IllegalStateException("No eligible join for the current plan; "
                        + "allowed join methods: " + joinPolicy.allowedMethods()
                        + ". Check equality keys, inner indexes, available buffers, "
                        + "and the heuristic join order.");
        }
        TablePlanner besttp = null;
        Plan bestplan = null;
        for (TablePlanner tp : tableplanners) {
            Plan plan = tp.makeProductPlan(current);
            if (bestplan == null || plan.recordsOutput() < bestplan.recordsOutput()) {
                besttp = tp;
                bestplan = plan;
            }
        }
        tableplanners.remove(besttp);
        return bestplan;
    }

    public void setPlanner(Planner p) {
        // for use in planning views, which
        // for simplicity this code doesn't do.
    }

}
