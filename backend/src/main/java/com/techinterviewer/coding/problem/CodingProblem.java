package com.techinterviewer.coding.problem;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A curated coding problem.
 *
 * @param id          slug, also the file name, for example {@code two-sum}; stored in {@code questions.coding_problem_id}
 * @param title       short name
 * @param difficulty  1 to 5, on the scale the interview engine decides levels on
 * @param statement   what the candidate reads (Markdown), without the examples
 * @param constraints input limits shown to the candidate
 * @param signature   the function to implement
 * @param comparison  how results are compared
 * @param timeLimitMs time limit of one test inside the sandbox
 * @param complexity  the reference solution's complexity, for the evaluator (server-only, like the key points)
 * @param keyPoints   what a good solution contains (server-only)
 * @param tests       examples first, then the hidden tests, in category order
 */
public record CodingProblem(
        String id,
        String title,
        int difficulty,
        String statement,
        List<String> constraints,
        Signature signature,
        Comparison comparison,
        int timeLimitMs,
        Complexity complexity,
        List<ExpectedKeyPoint> keyPoints,
        List<TestCase> tests) {

    public static final int MAX_STATEMENT_LENGTH = 3_000;
    public static final int MIN_TIME_LIMIT_MS = 100;
    public static final int MAX_TIME_LIMIT_MS = 10_000;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Time and space complexity, as written in Big-O notation. */
    public record Complexity(String time, String space) {

        public Complexity {
            Require.notBlank(time, "time", 40);
            Require.notBlank(space, "space", 40);
        }
    }

    public CodingProblem {
        Require.notBlank(id, "id", 60);
        if (!SLUG.matcher(id).matches()) {
            throw new IllegalArgumentException("problem id must be a slug but was '" + id + "'");
        }
        Require.notBlank(title, "title", 80);
        Require.between(difficulty, 1, 5, "difficulty");
        Require.notBlank(statement, "statement", MAX_STATEMENT_LENGTH);
        constraints = Immutables.list(constraints);
        constraints.forEach(constraint -> Require.notBlank(constraint, "constraint", 200));
        Require.nonNull(signature, "signature");
        Require.nonNull(comparison, "comparison");
        Require.between(timeLimitMs, MIN_TIME_LIMIT_MS, MAX_TIME_LIMIT_MS, "timeLimitMs");
        Require.nonNull(complexity, "complexity");
        keyPoints = Immutables.list(keyPoints);
        Require.between(keyPoints.size(), 1, 10, "number of key points");
        tests = Immutables.list(tests);
        validateTests(signature, tests);
    }

    private static void validateTests(Signature signature, List<TestCase> tests) {
        if (tests.stream().noneMatch(TestCase::visible)) {
            throw new IllegalArgumentException("a problem needs at least one example");
        }
        if (tests.stream().filter(test -> !test.visible()).count() < 3) {
            throw new IllegalArgumentException("a problem needs at least three hidden tests");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        TestCategory previous = TestCategory.EXAMPLE;
        for (TestCase test : tests) {
            if (!ids.add(test.id()) || !names.add(test.name())) {
                throw new IllegalArgumentException("test " + test.id() + " (" + test.name() + ") is not unique");
            }
            if (test.category().compareTo(previous) < 0) {
                throw new IllegalArgumentException("tests must be in category order; " + test.id() + " is out of order");
            }
            previous = test.category();
            if (test.arguments().size() != signature.parameters().size()) {
                throw new IllegalArgumentException("test " + test.id() + " has " + test.arguments().size() + " arguments, the signature "
                        + signature.parameters().size());
            }
            for (int i = 0; i < test.arguments().size(); i++) {
                Signature.Parameter parameter = signature.parameters().get(i);
                if (!parameter.type().accepts(test.arguments().get(i))) {
                    throw new IllegalArgumentException("test " + test.id() + ": argument " + parameter.name() + " is not a " + parameter.type().token());
                }
            }
            if (!signature.returns().accepts(test.expected())) {
                throw new IllegalArgumentException("test " + test.id() + ": the expected value is not a " + signature.returns().token());
            }
        }
    }

    public List<TestCase> examples() {
        return tests.stream().filter(TestCase::visible).toList();
    }
}
