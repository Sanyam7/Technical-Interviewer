package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;

/**
 * The evaluator's reading of a coding solution, stored in {@code evaluations.code_analysis}: the approach in a sentence
 * and the time and space complexity it actually has, in Big-O notation. It explains the algorithm and complexity ratings;
 * the score is computed from the ratings, not from these strings.
 */
public record CodeAnalysis(String approach, String timeComplexity, String spaceComplexity) {

    public static final int MAX_APPROACH_LENGTH = 500;
    public static final int MAX_COMPLEXITY_LENGTH = 60;

    public CodeAnalysis {
        Require.notBlank(approach, "approach", MAX_APPROACH_LENGTH);
        Require.notBlank(timeComplexity, "timeComplexity", MAX_COMPLEXITY_LENGTH);
        Require.notBlank(spaceComplexity, "spaceComplexity", MAX_COMPLEXITY_LENGTH);
    }
}
