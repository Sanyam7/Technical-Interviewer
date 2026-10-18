package com.techinterviewer.coding.service;

import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestOutcome;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.domain.TestStatus;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ExampleText;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.evaluation.TestEvidence;
import com.techinterviewer.interview.port.CodeTestEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** The two readings of a test run: text for the evaluator's prompt, numbers for the scoring rules. */
public final class CodeEvidence {

    private CodeEvidence() {
    }

    /** The numbers the scoring rules use. */
    public static TestEvidence forScoring(TestRunReport report) {
        boolean compiled = report.status() != RunStatus.COMPILATION_ERROR;
        return new TestEvidence(compiled, compiled ? report.functionalPassed() : 0, Math.max(1, report.functionalTotal()),
                compiled ? report.passed(TestCategory.EDGE) : 0, report.total(TestCategory.EDGE), report.performanceTimedOut());
    }

    /** What the evaluator reads: the counts, and every test that did not pass with the input that broke it. */
    public static CodeTestEvidence forPrompt(CodingProblem problem, TestRunReport report) {
        Map<String, TestCase> tests = problem.tests().stream().collect(Collectors.toMap(TestCase::id, Function.identity()));
        List<String> failures = new ArrayList<>();
        for (TestOutcome outcome : report.outcomes()) {
            if (outcome.status() == TestStatus.PASSED || failures.size() == CodeTestEvidence.MAX_FAILURES) {
                continue;
            }
            TestCase test = tests.get(outcome.testId());
            StringBuilder line = new StringBuilder().append(outcome.category()).append(" test \"").append(outcome.name()).append("\": ")
                    .append(outcome.status());
            if (outcome.message() != null) {
                line.append(" - ").append(outcome.message());
            }
            if (test != null && outcome.status() != TestStatus.NOT_RUN) {
                line.append(" Input: ").append(ExampleText.shorten(ExampleText.input(problem.signature(), test)))
                        .append("; expected ").append(ExampleText.value(test.expected()));
                if (outcome.actual() != null) {
                    line.append("; returned ").append(outcome.actual());
                }
            }
            failures.add(line.toString());
        }
        return new CodeTestEvidence(true, summary(report), failures, report.compileOutput(), problem.complexity().time(),
                problem.complexity().space());
    }

    /** No run: why, with the reference complexity when the problem is known. */
    public static CodeTestEvidence notRun(String reason, @Nullable CodingProblem problem) {
        return CodeTestEvidence.notRun(reason, problem == null ? null : problem.complexity().time(),
                problem == null ? null : problem.complexity().space());
    }

    static String summary(TestRunReport report) {
        return switch (report.status()) {
            case COMPILATION_ERROR -> "The code did not compile; no test ran (correctness 0).";
            default -> {
                StringBuilder text = new StringBuilder().append(report.functionalPassed()).append(" of ").append(report.functionalTotal())
                        .append(" functional tests passed");
                int edges = report.total(TestCategory.EDGE);
                if (edges > 0) {
                    text.append("; ").append(report.passed(TestCategory.EDGE)).append(" of ").append(edges).append(" edge-case tests passed");
                }
                int performance = report.total(TestCategory.PERFORMANCE);
                if (performance > 0) {
                    text.append("; ").append(report.performanceTimedOut() ? "a performance test exceeded its time limit"
                            : report.passed(TestCategory.PERFORMANCE) + " of " + performance + " performance tests passed");
                }
                if (report.status() != RunStatus.COMPLETED) {
                    text.append(" (the run ended early: ").append(report.status()).append(')');
                }
                yield text.append('.').toString();
            }
        };
    }
}
