package simpledb.opt;

import java.util.*;
import simpledb.tx.Transaction;
import simpledb.materialize.DistinctPlan;
import simpledb.materialize.SortPlan;
import simpledb.metadata.MetadataMgr;
import simpledb.parse.QueryData;
import simpledb.parse.Parser;
import simpledb.plan.*;

/**
 * A query planner that optimizes using a heuristic-based algorithm.
 * 
 * @author Edward Sciore
 */
public class HeuristicQueryPlanner implements QueryPlanner {
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
        new QueryValidator(mdm).validate(data, tx);
        Collection<TablePlanner> tableplanners = new ArrayList<>();
        simpledb.record.Schema inputSchema = new simpledb.record.Schema();

        // Step 1: Create a TablePlanner object for each mentioned table
        for (String tblname : data.tables()) {
            String viewdef = mdm.getViewDef(tblname, tx);
            TablePlanner tp;
            if (viewdef == null) {
                tp = new TablePlanner(tblname, data.pred(), tx, mdm, joinPolicy);
            } else {
                Plan view = createPlan(new Parser(viewdef).query(), tx);
                tp = new TablePlanner(tblname, view, data.pred(), tx, joinPolicy);
            }
            tableplanners.add(tp);
            inputSchema.addAll(tp.schema());
        }
        data.pred().validateTimeTypes(inputSchema);

        // Step 2: Choose the lowest-size plan to begin the join order
        Plan currentplan = getLowestSelectPlan(tableplanners);

        // Step 3: Repeatedly add a plan to the join order
        while (!tableplanners.isEmpty()) {
            Plan p = getLowestJoinPlan(currentplan, tableplanners);
            if (p != null)
                currentplan = p;
            else // no applicable join
                currentplan = getLowestProductPlan(currentplan, tableplanners);
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

    private Plan getLowestSelectPlan(Collection<TablePlanner> tableplanners) {
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

    private Plan getLowestJoinPlan(Plan current, Collection<TablePlanner> tableplanners) {
        TablePlanner besttp = null;
        Plan bestplan = null;
        for (TablePlanner tp : tableplanners) {
            Plan plan = tp.makeJoinPlan(current);
            // An unavailable candidate may become eligible after another join.
            // Search every remaining relation before deciding progress is blocked.
            if (plan != null && (bestplan == null || plan.recordsOutput() < bestplan.recordsOutput())) {
                besttp = tp;
                bestplan = plan;
            }
        }
        if (bestplan != null)
            tableplanners.remove(besttp);
        return bestplan;
    }

    private Plan getLowestProductPlan(Plan current, Collection<TablePlanner> tableplanners) {
        // A null join candidate may mean the policy excludes every eligible
        // method, rather than that this is a genuinely disconnected product.
        List<String> blocked = new ArrayList<>();
        for (TablePlanner tp : tableplanners) {
            if (tp.hasJoinPredicate(current))
                blocked.add(tp.joinDescription(current));
        }
        if (!blocked.isEmpty())
            throw new IllegalStateException("No compliant join continuation for the current plan; "
                    + "allowed join methods: " + joinPolicy.allowedMethods()
                    + "; current fields: " + current.schema().fields()
                    + "; blocked relations: " + blocked
                    + ". Check equality keys, inner indexes, available buffers, "
                    + "and the heuristic join order. This planner does not backtrack join orders.");
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
        // Retained for existing callers; views use this planner recursively.
    }

}
