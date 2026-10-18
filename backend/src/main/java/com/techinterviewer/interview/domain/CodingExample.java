package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/** A worked example of a coding problem, shown to the candidate. */
public record CodingExample(String input, String output, @Nullable String explanation) {

    public static final int MAX_LENGTH = 1000;

    public CodingExample {
        Require.notBlank(input, "input", MAX_LENGTH);
        Require.notBlank(output, "output", MAX_LENGTH);
        explanation = Require.nullOrNotBlank(explanation, "explanation", MAX_LENGTH);
    }
}
