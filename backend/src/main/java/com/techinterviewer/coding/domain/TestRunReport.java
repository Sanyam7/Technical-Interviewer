package com.techinterviewer.coding.domain;

import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What running a solution against a problem's tests showed. Computed by the server from what the harness reported and
 * the expected values it kept to itself.
 *
 * @param compileOutput   the compiler's (or loader's) message when the code did not compile
 * @param candidateOutput what the code printed itself, shortened (debugging output)
 * @param durationMs      wall-clock time of the run, container start included
 */
public record TestRunReport(
        String problemId,
        String language,
        RunStatus status,
        List<TestOutcome> outcomes,
        @Nullable String compileOutput,
        String candidateOutput,
        long durationMs) {

    public static final int MAX_COMPILE_OUTPUT = 4_000;
    public static final int MAX_CANDIDATE_OUTPUT = 4_000;

    public TestRunReport {
        Require.notBlank(problemId, "problemId", 60);
        Require.notBlank(language, "language", 20);
        Require.nonNull(status, "status");
        outcomes = Immutables.list(outcomes);
        compileOutput = compileOutput == null ? null : cut(compileOutput, MAX_COMPILE_OUTPUT);
        candidateOutput = cut(Require.nonNull(candidateOutput, "candidateOutput"), MAX_CANDIDATE_OUTPUT);
        Require.atLeast((int) Math.min(durationMs, Integer.MAX_VALUE), 0, "durationMs");
    }

    public int passed() {
        return (int) outcomes.stream().filter(outcome -> outcome.status() == TestStatus.PASSED).count();
    }

    public int total() {
        return outcomes.size();
    }

    /** Tests that decide correctness (every category but PERFORMANCE). */
    public int functionalTotal() {
        return (int) outcomes.stream().filter(outcome -> outcome.category().functional()).count();
    }

    public int functionalPassed() {
        return (int) outcomes.stream().filter(outcome -> outcome.category().functional() && outcome.status() == TestStatus.PASSED).count();
    }

    public int total(TestCategory category) {
        return (int) outcomes.stream().filter(outcome -> outcome.category() == category).count();
    }

    public int passed(TestCategory category) {
        return (int) outcomes.stream().filter(outcome -> outcome.category() == category && outcome.status() == TestStatus.PASSED).count();
    }

    /** A performance test exceeded its time limit: evidence that the solution is too slow for the constraints. */
    public boolean performanceTimedOut() {
        return outcomes.stream().anyMatch(outcome -> outcome.category() == TestCategory.PERFORMANCE && outcome.status() == TestStatus.TIMEOUT);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
