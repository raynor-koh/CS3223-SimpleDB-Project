package simpledb.plan;

import java.util.Arrays;
import java.util.List;

import simpledb.query.NestedLoopJoinScan;
import simpledb.query.Predicate;
import simpledb.query.Scan;
import simpledb.record.Schema;

/**
 * A plan implementing a nested-loops join.
 */
public class NestedLoopJoinPlan implements Plan {
    private Plan lhs;
    private Plan rhs;
    private Predicate joinPred;
    private Schema schema = new Schema();

    public NestedLoopJoinPlan(Plan lhs, Plan rhs, Predicate joinPred) {
        this.lhs = lhs;
        this.rhs = rhs;
        this.joinPred = joinPred;

        schema.addAll(lhs.schema());
        schema.addAll(rhs.schema());
    }

    @Override
    public Scan open() {
        return new NestedLoopJoinScan(lhs.open(), rhs.open(), joinPred);
    }

    @Override
    public int blocksAccessed() {
        return lhs.blocksAccessed()
                + lhs.recordsOutput() * rhs.blocksAccessed();
    }

    @Override
    public int recordsOutput() {
        int productSize = lhs.recordsOutput() * rhs.recordsOutput();
        return productSize / joinPred.reductionFactor(this);
    }

    @Override
    public int distinctValues(String fldname) {
        int values = inputDistinctValues(fldname);

        if (joinPred.equatesWithConstant(fldname) != null)
            return 1;

        String otherField = joinPred.equatesWithField(fldname);
        if (otherField != null)
            // Read the input estimate: recursing through the join predicate
            // follows A = B back to B = A indefinitely.
            return Math.min(values, inputDistinctValues(otherField));

        return values;
    }

    private int inputDistinctValues(String fldname) {
        if (lhs.schema().hasField(fldname))
            return lhs.distinctValues(fldname);
        return rhs.distinctValues(fldname);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public String operationDescription() {
        return "NestedLoopJoin(predicate=" + joinPred + ")";
    }

    @Override
    public List<Plan> children() {
        return Arrays.asList(lhs, rhs);
    }
}
