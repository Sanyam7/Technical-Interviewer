package com.techinterviewer.coding.problem;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One test of a problem: the arguments, the expected result and what it checks. Inputs written with a generator in the
 * problem file ({@code $range}, {@code $repeat}) are already expanded.
 *
 * @param id          {@code t1}, {@code t2}, ... in file order; stable while the file is
 * @param name        what it tests, shown in results (for example "single element")
 * @param explanation for an example: why the output is what it is
 */
public record TestCase(String id, String name, TestCategory category, List<JsonNode> arguments, JsonNode expected,
        @Nullable String explanation) {

    public static final int MAX_NAME_LENGTH = 80;

    public TestCase {
        Require.notBlank(id, "id", 10);
        Require.notBlank(name, "name", MAX_NAME_LENGTH);
        Require.nonNull(category, "category");
        arguments = Immutables.list(arguments);
        Require.nonNull(expected, "expected");
        explanation = Require.nullOrNotBlank(explanation, "explanation", 500);
    }

    /** An example is shown to the candidate; every other test is hidden while the interview runs. */
    public boolean visible() {
        return category == TestCategory.EXAMPLE;
    }
}
