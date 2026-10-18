package com.techinterviewer.coding.domain;

/** How a whole run ended. Stored in {@code code_test_runs.status} (checked). */
public enum RunStatus {
    /** Every test was attempted (each has its own status). */
    COMPLETED,
    /** The code did not compile, or could not be loaded; no test ran. */
    COMPILATION_ERROR,
    /** The run exceeded its wall-clock limit; tests not reached are NOT_RUN. */
    TIMED_OUT,
    /** The process ended without finishing, for example out of memory. */
    CRASHED,
    /** The code printed more than the sandbox keeps; the run was stopped. */
    OUTPUT_LIMIT
}
