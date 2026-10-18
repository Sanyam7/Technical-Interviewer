package com.techinterviewer.coding.problem;

/** How a returned value is compared with the expected one. */
public enum Comparison {
    /** Equal as JSON values (numbers by value: {@code 2} equals {@code 2.0}). */
    EXACT,
    /** The top-level arrays hold the same elements, in any order. */
    UNORDERED
}
