package com.techinterviewer.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestOutcome;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.domain.TestStatus;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.evaluation.TestEvidence;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.port.CodeTestEvidence;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CodeEvidenceTest {

    private static final ProblemBank BANK = ProblemBank.load();
    private static final CodingProblem TWO_SUM = BANK.find("two-sum").orElseThrow();

    /** A run of two-sum where the named tests did not pass and every other one did. */
    private static TestRunReport run(RunStatus status, Function<TestCase, TestStatus> result) {
        List<TestOutcome> outcomes = TWO_SUM.tests().stream()
                .map(test -> {
                    TestStatus outcome = result.apply(test);
                    return new TestOutcome(test.id(), test.name(), test.category(), test.visible(), outcome, 3L,
                            outcome == TestStatus.PASSED ? null : "Wrong answer.", outcome == TestStatus.FAILED ? "[9,9]" : null);
                })
                .toList();
        return new TestRunReport("two-sum", "java", status, outcomes, null, "", 1200);
    }

    @Test
    void countsFunctionalEdgeAndPerformanceTestsSeparatelyForScoring() {
        TestRunReport report = run(RunStatus.COMPLETED, test -> switch (test.name()) {
            case "zeros" -> TestStatus.FAILED;
            case "pair at the end of a large array" -> TestStatus.TIMEOUT;
            default -> TestStatus.PASSED;
        });

        TestEvidence evidence = CodeEvidence.forScoring(report);

        assertThat(evidence).isEqualTo(new TestEvidence(true, 7, 8, 2, 3, true));
        assertThat(evidence.correctnessRating()).isEqualTo(3);
    }

    @Test
    void aCompilationErrorPassesNothing() {
        TestRunReport report = new TestRunReport("two-sum", "java", RunStatus.COMPILATION_ERROR,
                run(RunStatus.COMPLETED, test -> TestStatus.NOT_RUN).outcomes(), "Solution.java:1: error", "", 900);

        assertThat(CodeEvidence.forScoring(report)).isEqualTo(new TestEvidence(false, 0, 8, 0, 3, false));
    }

    @Test
    void tellsTheEvaluatorWhichTestsFailedWithTheInputThatBrokeTheCode() {
        TestRunReport report = run(RunStatus.COMPLETED, test -> test.name().equals("zeros") ? TestStatus.FAILED : TestStatus.PASSED);

        CodeTestEvidence evidence = CodeEvidence.forPrompt(TWO_SUM, report);

        assertThat(evidence.ran()).isTrue();
        assertThat(evidence.summary()).isEqualTo("7 of 8 functional tests passed; 2 of 3 edge-case tests passed; 1 of 1 performance tests passed.");
        assertThat(evidence.failures()).containsExactly(
                "EDGE test \"zeros\": FAILED - Wrong answer. Input: nums = [0,4,3,0], target = 0; expected [0,3]; returned [9,9]");
        assertThat(evidence.referenceTime()).isEqualTo("O(n)");
    }

    @Test
    void saysWhenTheRunEndedEarly() {
        TestRunReport report = run(RunStatus.TIMED_OUT, test -> TestStatus.NOT_RUN);

        assertThat(CodeEvidence.forPrompt(TWO_SUM, report).summary()).contains("the run ended early: TIMED_OUT");
    }

    @Test
    void aNotRunEvidenceKeepsTheReferenceComplexity() {
        CodeTestEvidence evidence = CodeEvidence.notRun("Execution is off.", TWO_SUM);

        assertThat(evidence.ran()).isFalse();
        assertThat(evidence.referenceSpace()).isEqualTo("O(n)");
    }

    @Test
    void aBankProblemBecomesStaticQuestionContentWithExamplesAndStarterCode() {
        QuestionContent content = CodingQuestions.content(TWO_SUM, CodeLanguage.TYPESCRIPT, "coding.v2");

        assertThat(content.origin()).isEqualTo(QuestionOrigin.STATIC);
        assertThat(content.codingProblemId()).isEqualTo("two-sum");
        assertThat(content.generationLlmCallId()).isNull();
        assertThat(content.prompt()).isEqualTo(TWO_SUM.statement());
        assertThat(content.expectedKeyPoints()).isEqualTo(TWO_SUM.keyPoints());
        assertThat(content.details().language()).isEqualTo("typescript");
        assertThat(content.details().starterSignature()).contains("function twoSum(nums: number[], target: number): number[]");
        assertThat(content.details().examples()).hasSize(2);
        assertThat(content.details().examples().getFirst().input()).isEqualTo("nums = [2,7,11,15], target = 9");
        assertThat(content.details().examples().getFirst().output()).isEqualTo("[0,1]");
        assertThat(content.details().constraints()).isEqualTo(TWO_SUM.constraints());
    }

    @Test
    void choosesAProblemTheInterviewHasNotAskedAtExactlyTheDecidedLevel() {
        CodingQuestions questions = new CodingQuestions(BANK);
        UUID interview = UUID.randomUUID();

        Optional<QuestionContent> first = questions.choose(interview, 2, Set.of(), "java", "coding.v2");
        Optional<QuestionContent> second = questions.choose(interview, 2, Set.of(first.orElseThrow().codingProblemId()), "java", "coding.v2");

        assertThat(BANK.find(first.orElseThrow().codingProblemId()).orElseThrow().difficulty()).isEqualTo(2);
        assertThat(second.orElseThrow().codingProblemId()).isNotEqualTo(first.get().codingProblemId());
        assertThat(questions.choose(interview, 2, Set.of(), "cobol", "coding.v2")).isEmpty();
    }
}
