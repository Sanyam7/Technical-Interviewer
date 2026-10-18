package com.techinterviewer.coding.problem;

/**
 * What a test checks. The order is the order tests run in, so a performance test that exhausts its time limit cannot
 * hide the result of a functional one.
 */
public enum TestCategory {
    /** Shown to the candidate with its expected output, and run by "Run examples". */
    EXAMPLE,
    /** A typical input the candidate does not see. */
    GENERAL,
    /** A boundary: empty, single element, duplicates, extremes of the constraints. Caps the EDGE_CASES rating. */
    EDGE,
    /** A large input within the constraints; exceeding its time limit caps the TIME_COMPLEXITY rating. */
    PERFORMANCE;

    /** Whether the test decides correctness. A performance test checks speed; its answer is checked too, but it counts for time complexity. */
    public boolean functional() {
        return this != PERFORMANCE;
    }
}
