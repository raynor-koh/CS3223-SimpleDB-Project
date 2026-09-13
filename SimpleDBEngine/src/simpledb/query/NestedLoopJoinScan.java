package simpledb.query;

public class NestedLoopJoinScan implements Scan {
    private Scan lhs;
    private Scan rhs;
    private Predicate joinPred;
    private boolean hasCurrentLhs;

    public NestedLoopJoinScan(Scan lhs, Scan rhs, Predicate joinPred) {
        this.lhs = lhs;
        this.rhs = rhs;
        this.joinPred = joinPred;
        beforeFirst();
    }

    @Override
    public void beforeFirst() {
        lhs.beforeFirst();
        rhs.beforeFirst();
        hasCurrentLhs = false;
    }

    @Override
    public boolean next() {
        while (true) {
            // Get a left-hand record if there is not one already.
            if (!hasCurrentLhs) {
                if (!lhs.next())
                    return false;

                hasCurrentLhs = true;
                rhs.beforeFirst();
            }

            // Test every right-hand record against the current left record.
            while (rhs.next()) {
                if (joinPred.isSatisfied(this))
                    return true;
            }

            // The right scan is exhausted for this left record.
            // Advance the left scan on the next loop iteration.
            hasCurrentLhs = false;
        }
    }

    @Override
    public int getInt(String fldname) {
        if (lhs.hasField(fldname))
            return lhs.getInt(fldname);
        return rhs.getInt(fldname);
    }

    @Override
    public String getString(String fldname) {
        if (lhs.hasField(fldname))
            return lhs.getString(fldname);
        return rhs.getString(fldname);
    }

    @Override
    public Constant getVal(String fldname) {
        if (lhs.hasField(fldname))
            return lhs.getVal(fldname);
        return rhs.getVal(fldname);
    }

    @Override
    public boolean hasField(String fldname) {
        return lhs.hasField(fldname) || rhs.hasField(fldname);
    }

    @Override
    public void close() {
        lhs.close();
        rhs.close();
    }
}
