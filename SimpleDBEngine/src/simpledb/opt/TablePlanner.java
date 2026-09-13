package simpledb.opt;

import java.util.Map;
import simpledb.tx.Transaction;
import simpledb.record.*;
import simpledb.query.*;
import simpledb.metadata.*;
import simpledb.index.planner.*;
import simpledb.materialize.MergeJoinPlan;
import simpledb.multibuffer.MultibufferProductPlan;
import simpledb.plan.*;

/**
 * This class contains methods for planning a single table.
 * 
 * @author Edward Sciore
 */
class TablePlanner {
    private TablePlan myplan;
    private Predicate mypred;
    private Schema myschema;
    private Map<String, IndexInfo> indexes;
    private Transaction tx;

    /**
     * Creates a new table planner.
     * The specified predicate applies to the entire query.
     * The table planner is responsible for determining
     * which portion of the predicate is useful to the table,
     * and when indexes are useful.
     * 
     * @param tblname the name of the table
     * @param mypred  the query predicate
     * @param tx      the calling transaction
     */
    public TablePlanner(String tblname, Predicate mypred, Transaction tx, MetadataMgr mdm) {
        this.mypred = mypred;
        this.tx = tx;
        myplan = new TablePlan(tx, tblname, mdm);
        myschema = myplan.schema();
        indexes = mdm.getIndexInfo(tblname, tx);
    }

    /**
     * Constructs a select plan for the table.
     * The plan will use an indexselect, if possible.
     * 
     * @return a select plan for the table.
     */
    public Plan makeSelectPlan() {
        Plan p = makeIndexSelect();
        if (p == null)
            p = myplan;
        return addSelectPred(p);
    }

    /**
     * Constructs a join plan of the specified plan
     * and the table. The plan will use an indexjoin, if possible.
     * (Which means that if an indexselect is also possible,
     * the indexjoin operator takes precedence.)
     * The method returns null if no join is possible.
     * 
     * @param current the specified plan
     * @return a join plan of the plan and this table
     */
    public Plan makeJoinPlan(Plan current) {
        Schema currsch = current.schema();
        Predicate joinpred = mypred.joinSubPred(myschema, currsch);

        // No predicate connects this table to the current plan.
        if (joinpred == null)
            return null;

        // Prefer an index join when the new table has a usable index.
        Plan p = makeIndexJoin(current, currsch);

        // Otherwise, use sort-merge for an equality join.
        if (p == null)
            p = makeMergeJoin(current, currsch);

        // Any remaining join predicate uses nested loops.
        if (p == null)
            p = makeNestedLoopJoin(current, currsch);

        return p;
    }

    /**
     * Constructs a product plan of the specified plan and
     * this table.
     * 
     * @param current the specified plan
     * @return a product plan of the specified plan and this table
     */
    public Plan makeProductPlan(Plan current) {
        Plan p = addSelectPred(myplan);
        return new MultibufferProductPlan(tx, current, p);
    }

    private Plan makeIndexSelect() {
        for (String fldname : indexes.keySet()) {
            Constant val = mypred.equatesWithConstant(fldname);
            if (val != null) {
                IndexInfo ii = indexes.get(fldname);
                System.out.println("index on " + fldname + " used");
                return new IndexSelectPlan(myplan, ii, val);
            }
        }
        return null;
    }

    private Plan makeIndexJoin(Plan current, Schema currsch) {
        for (String fldname : indexes.keySet()) {
            String outerfield = mypred.equatesWithField(fldname);
            if (outerfield != null && currsch.hasField(outerfield)) {
                IndexInfo ii = indexes.get(fldname);
                Plan p = new IndexJoinPlan(current, myplan, ii, outerfield);
                p = addSelectPred(p);
                return addJoinPred(p, currsch);
            }
        }
        return null;
    }

    /**
     * Constructs a nested-loops join between the current plan and this table.
     * The join predicate is evaluated inside NestedLoopJoinScan.
     */
    private Plan makeNestedLoopJoin(Plan current, Schema currsch) {
        Predicate joinpred = mypred.joinSubPred(currsch, myschema);

        // Predicates involving only this table should be applied before joining.
        Plan rhs = addSelectPred(myplan);

        return new NestedLoopJoinPlan(current, rhs, joinpred);
    }

    /**
     * Constructs a sort-merge join when an equality predicate connects
     * a field in the current plan to a field in this table.
     *
     * @param current the current left-deep query plan
     * @param currsch the schema of the current plan
     * @return a merge-join plan, or null if no equality join key exists
     */
    private Plan makeMergeJoin(Plan current, Schema currsch) {
        for (String innerField : myschema.fields()) {
            String outerField = mypred.equatesWithField(innerField);

            // The equality must connect this table to the current plan.
            if (outerField != null && currsch.hasField(outerField)) {
                // Apply predicates that concern only the new table before sorting.
                Plan rhs = addSelectPred(myplan);

                Plan p = new MergeJoinPlan(
                        tx,
                        current,
                        rhs,
                        outerField,
                        innerField);

                /*
                 * The merge join enforces the selected equality key. This SelectPlan
                 * also enforces any additional join terms, such as:
                 *
                 * DId = DeptId and YearOffered >= 2020
                 *
                 * Rechecking DId = DeptId is harmless and keeps the implementation
                 * correct without needing to remove one term from Predicate.
                 */
                return addJoinPred(p, currsch);
            }
        }
        return null;
    }

    private Plan addSelectPred(Plan p) {
        Predicate selectpred = mypred.selectSubPred(myschema);
        if (selectpred != null)
            return new SelectPlan(p, selectpred);
        else
            return p;
    }

    private Plan addJoinPred(Plan p, Schema currsch) {
        Predicate joinpred = mypred.joinSubPred(currsch, myschema);
        if (joinpred != null)
            return new SelectPlan(p, joinpred);
        else
            return p;
    }
}
