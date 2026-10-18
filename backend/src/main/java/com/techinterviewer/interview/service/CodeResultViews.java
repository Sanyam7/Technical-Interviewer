package com.techinterviewer.interview.service;

import com.techinterviewer.coding.domain.TestOutcome;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ExampleText;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.interview.api.dto.CodeTestResultView;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Test outcomes as a candidate may see them: the data of a visible example, only the name of a hidden test. */
final class CodeResultViews {

    private CodeResultViews() {
    }

    static List<CodeTestResultView> of(TestRunReport report, @Nullable CodingProblem problem) {
        Map<String, TestCase> tests = problem == null ? Map.of()
                : problem.tests().stream().collect(Collectors.toMap(TestCase::id, Function.identity()));
        return report.outcomes().stream().map(outcome -> view(outcome, problem, tests.get(outcome.testId()))).toList();
    }

    private static CodeTestResultView view(TestOutcome outcome, @Nullable CodingProblem problem, @Nullable TestCase test) {
        boolean shown = outcome.visible() && problem != null && test != null;
        return new CodeTestResultView(
                outcome.name(),
                outcome.category().name(),
                outcome.status().name(),
                shown ? ExampleText.input(problem.signature(), test) : null,
                shown ? ExampleText.value(test.expected()) : null,
                shown ? outcome.actual() : null,
                outcome.message(),
                outcome.timeMs());
    }
}
