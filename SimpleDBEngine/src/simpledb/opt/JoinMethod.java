package simpledb.opt;

/** Physical join algorithms understood by the heuristic planner. */
public enum JoinMethod {
    INDEX,
    HASH,
    MERGE,
    NESTED_LOOP
}
