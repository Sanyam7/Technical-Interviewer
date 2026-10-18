package com.techinterviewer.coding.domain;

/** The result of one test. Stored by name in {@code code_test_runs.outcomes}: never rename a constant. */
public enum TestStatus {
    /** The function returned the expected value within the time limit. */
    PASSED,
    /** It returned another value. */
    FAILED,
    /** It threw an exception (or the value could not be read). */
    ERROR,
    /** It exceeded the time limit of a test. */
    TIMEOUT,
    /** It was not reached: an earlier test timed out (Java), or the run ended early. */
    NOT_RUN
}
