package simpledb.plan;

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
        int values;

        if (lhs.schema().hasField(fldname))
            values = lhs.distinctValues(fldname);
        else
            values = rhs.distinctValues(fldname);

        if (joinPred.equatesWithConstant(fldname) != null)
            return 1;

        String otherField = joinPred.equatesWithField(fldname);
        if (otherField != null)
            return Math.min(values, distinctValues(otherField));

        return values;
    }

    @Override
    public Schema schema() {
        return schema;
    }
}
