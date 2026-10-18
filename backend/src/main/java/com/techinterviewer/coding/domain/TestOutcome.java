package com.techinterviewer.coding.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/**
 * One test of a run, as stored. Inputs and expected values are not stored: they are in the problem bank.
 *
 * @param testId   the problem's test id ({@code t1}, ...)
 * @param name     what it tests
 * @param visible  an example the candidate saw while answering
 * @param timeMs   time the function took, as the harness measured it; null when it did not run
 * @param message  the exception, or why it did not pass; null when it passed
 * @param actual   what the function returned, shortened; null when it returned nothing usable
 */
public record TestOutcome(
        String testId,
        String name,
        TestCategory category,
        boolean visible,
        TestStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long timeMs,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String actual) {

    public static final int MAX_TEXT = 300;

    public TestOutcome {
        Require.notBlank(testId, "testId", 10);
        Require.notBlank(name, "name", 80);
        Require.nonNull(category, "category");
        Require.nonNull(status, "status");
        message = message == null ? null : shorten(message);
        actual = actual == null ? null : shorten(actual);
    }

    private static String shorten(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }
}
