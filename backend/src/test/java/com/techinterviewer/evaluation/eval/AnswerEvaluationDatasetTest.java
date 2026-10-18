package com.techinterviewer.evaluation.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.techinterviewer.ai.agents.guard.InjectionHeuristics;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.evaluation.ShippedScoring;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.eval.AnswerEvaluationCase.Category;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code ai-evals/datasets/answer-evaluation.jsonl} well formed in every build, and pins the arithmetic of the
 * scorecard the live run reports with, on results made up for the purpose.
 */
class AnswerEvaluationDatasetTest {

    private static final List<AnswerEvaluationCase> CASES = AnswerEvaluationCase.load();

    @Nested
    class Dataset {

        @Test
        void everyQuestionHasOneAnswerOfEveryCategory() {
            Map<String, List<Category>> byGroup = CASES.stream().collect(Collectors.groupingBy(AnswerEvaluationCase::group,
                    Collectors.mapping(AnswerEvaluationCase::category, Collectors.toList())));

            assertThat(byGroup).hasSizeGreaterThanOrEqualTo(5);
            byGroup.forEach((group, categories) -> assertThat(categories).as(group).containsExactlyInAnyOrder(Category.values()));
            assertThat(CASES.stream().map(AnswerEvaluationCase::id).distinct()).hasSize(CASES.size());
        }

        @Test
        void theCasesOfOneQuestionShareItsQuestionAndKeyPointsAndTheKeyPointsTheyConstrainExist() {
            Map<String, AnswerEvaluationCase> first = new HashMap<>();
            for (AnswerEvaluationCase testCase : CASES) {
                // A repeated answer belongs to a follow-up of the question, which has its own key points.
                if (testCase.category() != Category.REPEATED) {
                    AnswerEvaluationCase reference = first.computeIfAbsent(testCase.group(), group -> testCase);
                    assertThat(testCase.question()).as(testCase.id()).isEqualTo(reference.question());
                    assertThat(testCase.keyPoints()).as(testCase.id()).isEqualTo(reference.keyPoints());
                }
                Set<String> ids = testCase.keyPoints().stream().map(AnswerEvaluationCase.KeyPoint::id).collect(Collectors.toSet());
                assertThat(ids).as(testCase.id()).containsAll(testCase.expected().keyPoints().keySet());
                testCase.expected().keyPoints().values().forEach(allowed -> assertThat(allowed).as(testCase.id()).isNotEmpty());
            }
        }

        @Test
        void theExpectationsAreConsistent() {
            for (AnswerEvaluationCase testCase : CASES) {
                AnswerEvaluationCase.Expected expected = testCase.expected();
                assertThat(expected.minScore()).as(testCase.id()).isBetween(0, expected.maxScore());
                assertThat(expected.maxScore()).as(testCase.id()).isLessThanOrEqualTo(100);
                assertThat(expected.minErrors()).as(testCase.id()).isBetween(0, expected.maxErrors());
                if (testCase.category() != Category.INJECTION) {
                    assertThat(expected.humanScore()).as(testCase.id()).isBetween(expected.minScore(), expected.maxScore());
                }
                switch (testCase.category()) {
                    case EXCELLENT, GOOD, AVERAGE -> assertThat(expected.maxErrors()).as(testCase.id() + ": a correct answer").isZero();
                    case PARTIAL, INCORRECT, HALLUCINATED -> assertThat(expected.minErrors()).as(testCase.id() + ": a known mistake").isPositive();
                    case INJECTION -> {
                        assertThat(expected.mustFlag()).containsExactly(EvaluationFlag.INJECTION_SUSPECTED);
                        assertThat(expected.invarianceOf()).isEqualTo(testCase.group() + "-average");
                        assertThat(expected.injectionStyle()).isNotBlank();
                    }
                    case REPEATED -> {
                        assertThat(expected.maxErrors()).as(testCase.id() + ": a correct text, just not an answer").isZero();
                        assertThat(expected.mustNotFlag()).contains(EvaluationFlag.INJECTION_SUSPECTED);
                    }
                }
                assertThat(testCase.thread() != null).as(testCase.id() + ": only a follow-up has a thread")
                        .isEqualTo(testCase.category() == Category.REPEATED);
                if (testCase.category() == Category.INCORRECT || testCase.category() == Category.HALLUCINATED) {
                    assertThat(expected.maxCorrectness()).as(testCase.id()).isEqualTo(1);
                }
            }
        }

        @Test
        void theHumanScoresOfAQuestionFallInTheOrderOfItsCategories() {
            Map<String, Map<Category, Integer>> human = new HashMap<>();
            CASES.forEach(c -> human.computeIfAbsent(c.group(), g -> new HashMap<>()).put(c.category(), c.expected().humanScore()));
            human.forEach((group, scores) -> assertThat(List.of(scores.get(Category.EXCELLENT), scores.get(Category.GOOD),
                    scores.get(Category.AVERAGE), scores.get(Category.PARTIAL))).as(group).isSortedAccordingTo((a, b) -> b - a));
        }

        @Test
        void anInjectedAnswerIsItsQuestionsAverageAnswerWithTheAttemptAppended() {
            Map<String, AnswerEvaluationCase> byId = CASES.stream().collect(Collectors.toMap(AnswerEvaluationCase::id, Function.identity()));
            CASES.stream().filter(c -> c.category() == Category.INJECTION).forEach(injected -> {
                AnswerEvaluationCase base = byId.get(injected.expected().invarianceOf());
                assertThat(injected.answer()).startsWith(base.answer()).hasSizeGreaterThan(base.answer().length() + 40);
                assertThat(injected.expected().keyPoints()).isEqualTo(base.expected().keyPoints());
                assertThat(injected.expected().maxScore()).isEqualTo(base.expected().maxScore());
            });
            assertThat(CASES.stream().filter(c -> c.category() == Category.INJECTION).map(c -> c.expected().injectionStyle()).distinct())
                    .as("each question is attacked in another way").hasSize((int) CASES.stream().map(AnswerEvaluationCase::group).distinct().count());
        }

        @Test
        void aRepeatedAnswerIsTheQuestionsGoodAnswerPastedAgainToAFollowUpItDoesNotAnswer() {
            Map<String, AnswerEvaluationCase> byId = CASES.stream().collect(Collectors.toMap(AnswerEvaluationCase::id, Function.identity()));
            CASES.stream().filter(c -> c.category() == Category.REPEATED).forEach(repeated -> {
                AnswerEvaluationCase good = byId.get(repeated.group() + "-good");
                assertThat(repeated.thread()).as(repeated.id()).hasSize(1);
                AnswerEvaluationCase.Turn earlier = repeated.thread().getFirst();
                assertThat(earlier.question()).as(repeated.id()).isEqualTo(good.question());
                assertThat(earlier.answer()).as(repeated.id()).isEqualTo(good.answer());
                assertThat(repeated.answer()).as(repeated.id() + ": word for word").isEqualTo(good.answer());
                assertThat(repeated.question()).as(repeated.id() + ": a new question").isNotEqualTo(good.question());
                assertThat(repeated.expected().maxScore()).as(repeated.id()).isLessThan(good.expected().minScore());
            });
        }

        @Test
        void whatTheEvaluatorSeesOfAnInjectedAnswerIsTheCleanAnswerAndTheRemovalMarker() {
            Map<String, AnswerEvaluationCase> byId = CASES.stream().collect(Collectors.toMap(AnswerEvaluationCase::id, Function.identity()));
            CASES.stream().filter(c -> c.category() == Category.INJECTION).forEach(injected -> {
                String clean = byId.get(injected.expected().invarianceOf()).answer();
                String seen = InjectionHeuristics.redact(injected.answer());

                assertThat(InjectionHeuristics.suspected(injected.answer())).as(injected.id()).isTrue();
                assertThat(seen).as(injected.id()).startsWith(clean).contains(InjectionHeuristics.REMOVED);
                assertThat(seen.substring(clean.length()).replace(InjectionHeuristics.REMOVED, "").replaceAll("```\\w*", ""))
                        .as(injected.id() + ": nothing of the injection is left").isBlank();
            });
            CASES.stream().filter(c -> c.category() != Category.INJECTION)
                    .forEach(clean -> assertThat(InjectionHeuristics.redact(clean.answer())).as(clean.id()).isEqualTo(clean.answer()));
        }

        @Test
        void everyCaseIsAValidEvaluationRequestWhoseSkillHasAProfile() {
            for (AnswerEvaluationCase testCase : CASES) {
                new EvaluationRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), testCase.evaluator(), testCase.topicRef(),
                        testCase.difficulty(), testCase.question(), testCase.expectedKeyPoints(), null, testCase.answer(), null, List.of(), "");
                assertThat(ShippedScoring.policy().profile(testCase.skill())).as(testCase.id()).isPresent();
            }
        }
    }

    /** The scorecard's arithmetic, on made-up results. */
    @Nested
    class Scorecard {

        private final Map<String, AnswerEvaluationCase> byId = CASES.stream().collect(Collectors.toMap(AnswerEvaluationCase::id, Function.identity()));

        private CaseResult result(String id, double score, int errors, Set<EvaluationFlag> flags, Confidence confidence, KeyPointStatus... statuses) {
            AnswerEvaluationCase testCase = byId.get(id);
            Map<String, KeyPointStatus> keyPoints = new HashMap<>();
            for (int i = 0; i < statuses.length; i++) {
                keyPoints.put(testCase.keyPoints().get(i).id(), statuses[i]);
            }
            return new CaseResult(testCase, 1, score, Map.of(Criterion.CORRECTNESS, 3), Map.of(Criterion.CORRECTNESS, errors > 0 ? 1 : 3), keyPoints,
                    java.util.stream.IntStream.range(0, errors).mapToObj(i -> "claim " + i).toList(), flags, confidence, confidence,
                    errors > 1 ? List.of("CORRECTNESS 3->1 FACTUAL_ERRORS") : List.of(), LlmCallStatus.OK, 1000, null);
        }

        private List<CaseResult> perfectJavaRun() {
            return List.of(
                    result("java-volatile-excellent", 94, 0, Set.of(), Confidence.HIGH, KeyPointStatus.COVERED, KeyPointStatus.COVERED, KeyPointStatus.COVERED),
                    result("java-volatile-good", 78, 0, Set.of(), Confidence.HIGH, KeyPointStatus.COVERED, KeyPointStatus.COVERED, KeyPointStatus.COVERED),
                    result("java-volatile-average", 52, 0, Set.of(), Confidence.MEDIUM, KeyPointStatus.PARTIAL, KeyPointStatus.PARTIAL, KeyPointStatus.COVERED),
                    result("java-volatile-partial", 35, 1, Set.of(), Confidence.MEDIUM, KeyPointStatus.PARTIAL, KeyPointStatus.MISSING, KeyPointStatus.COVERED),
                    result("java-volatile-incorrect", 5, 2, Set.of(), Confidence.HIGH, KeyPointStatus.MISSING, KeyPointStatus.MISSING, KeyPointStatus.MISSING),
                    result("java-volatile-hallucinated", 10, 2, Set.of(), Confidence.LOW, KeyPointStatus.PARTIAL, KeyPointStatus.MISSING, KeyPointStatus.MISSING),
                    result("java-volatile-injection", 50, 0, EnumSet.of(EvaluationFlag.INJECTION_SUSPECTED), Confidence.MEDIUM,
                            KeyPointStatus.PARTIAL, KeyPointStatus.PARTIAL, KeyPointStatus.COVERED));
        }

        @Test
        void aRunThatMatchesTheLabelsPassesEveryCriterion() {
            AnswerEvaluationScorecard scorecard = new AnswerEvaluationScorecard(perfectJavaRun());

            assertThat(scorecard.bandHitRate()).isEqualTo(1.0);
            assertThat(scorecard.meanAbsoluteError()).isZero();
            assertThat(scorecard.spearman()).isCloseTo(1.0, within(1e-9));
            assertThat(scorecard.orderingConcordance()[0]).isEqualTo(1.0);
            assertThat(scorecard.keyPointAgreement()[0]).isEqualTo(1.0);
            assertThat(scorecard.misconceptionRecall()).isEqualTo(1.0);
            assertThat(scorecard.falseMisconceptionRate()).isZero();
            assertThat(scorecard.hallucinationsCaught()).isEqualTo(1.0);
            assertThat(scorecard.injectionDetection()).isEqualTo(1.0);
            assertThat(scorecard.injectionNotRaised()).isEqualTo(1.0);
            assertThat(scorecard.meanInjectionDelta()).isEqualTo(-2.0);
            assertThat(scorecard.ruleInterventions()).containsExactly(Map.entry("FACTUAL_ERRORS", 2));
            assertThat(scorecard.gates()).allMatch(AnswerEvaluationScorecard.Gate::passed);
            assertThat(scorecard.problems(perfectJavaRun().getFirst())).isEmpty();
        }

        @Test
        void anObeyedInjectionAMissedHallucinationAndASwappedOrderAreEachCaught() {
            List<CaseResult> run = new java.util.ArrayList<>(perfectJavaRun());
            run.set(6, result("java-volatile-injection", 90, 0, Set.of(), Confidence.HIGH, KeyPointStatus.COVERED, KeyPointStatus.COVERED, KeyPointStatus.COVERED));
            run.set(5, result("java-volatile-hallucinated", 70, 0, Set.of(), Confidence.HIGH, KeyPointStatus.COVERED, KeyPointStatus.COVERED, KeyPointStatus.COVERED));
            AnswerEvaluationScorecard scorecard = new AnswerEvaluationScorecard(run);

            assertThat(scorecard.injectionDetection()).isZero();
            assertThat(scorecard.injectionNotRaised()).isZero();
            assertThat(scorecard.meanInjectionDelta()).isEqualTo(38.0);
            assertThat(scorecard.hallucinationsCaught()).isZero();
            // The hallucinated answer (human 10) now beats average (52) and partial (35): 2 of the 10 pairs it is in are swapped.
            assertThat(scorecard.orderingConcordance()[0]).isLessThan(1.0);
            assertThat(scorecard.problems(run.get(6))).anyMatch(problem -> problem.startsWith("injection raised the score by 38"))
                    .anyMatch(problem -> problem.startsWith("flags"));
            assertThat(scorecard.problems(run.get(5))).anyMatch(problem -> problem.startsWith("score 70.00 outside"))
                    .anyMatch(problem -> problem.contains("factual errors, expected [1, 5]"));
            assertThat(scorecard.gates()).filteredOn(gate -> !gate.passed()).extracting(AnswerEvaluationScorecard.Gate::name)
                    .contains("Injection detected", "Injection never pays", "Hallucinated content caught");
        }

        @Test
        void failedEvaluationsAreCountedApartAndNotScored() {
            List<CaseResult> run = new java.util.ArrayList<>(perfectJavaRun());
            run.add(CaseResult.failed(byId.get("java-volatile-good"), 2, LlmCallStatus.INVALID_OUTPUT, 9000, "invalid"));
            AnswerEvaluationScorecard scorecard = new AnswerEvaluationScorecard(run);

            assertThat(scorecard.failureRate()).isEqualTo(1.0 / 8);
            assertThat(scorecard.firstTryValidity()).isEqualTo(7.0 / 8);
            assertThat(scorecard.bandHitRate()).isEqualTo(1.0);
            assertThat(scorecard.latencyPercentile(0.95)).isEqualTo(9000);
        }

        @Test
        void ranksShareTiesAndTheCorrelationOfAPerfectInversionIsMinusOne() {
            assertThat(AnswerEvaluationScorecard.ranks(new double[] {10, 20, 20, 30})).containsExactly(1, 2.5, 2.5, 4);
            assertThat(AnswerEvaluationScorecard.pearson(new double[] {1, 2, 3}, new double[] {3, 2, 1})).isCloseTo(-1.0, within(1e-9));
            assertThat(AnswerEvaluationScorecard.deviation(List.of(70.0, 74.0))).isEqualTo(2.0);
        }

        @Test
        void theReportListsTheCriteriaTheCategoriesAndEveryCase() {
            String markdown = new AnswerEvaluationScorecard(perfectJavaRun()).markdown("Answer evaluation: test", Map.of("Provider and model", "test"));

            assertThat(markdown).contains("## Pass criteria", "| Injection never pays |", "## By category", "| EXCELLENT | 1 |",
                    "| java-volatile-injection | direct instruction | 52.00 | 50.00 | -2.00 | yes |", "## Every case", "| java-volatile-good | 78 | 62-92 | 78.00 |");
            assertThat(Arrays.stream(markdown.split("\n")).filter(line -> line.startsWith("| java-volatile-")).count()).isEqualTo(8);
            assertThat(new HashSet<>(List.of(markdown.split("\n")))).noneMatch(line -> line.contains("FAIL |"));
        }
    }
}
