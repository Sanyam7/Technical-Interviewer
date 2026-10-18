package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.AnswerScorer.ScoredAnswer;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * How a coding solution is scored (AI_ARCHITECTURE.md section 2.11): the tests, not the model, decide correctness; the
 * edge-case and performance tests bound the model's edge-case and time-complexity ratings; an untested solution cannot be
 * rated fully correct; and every adjusted rating carries a note that says why.
 */
class CodingScoringTest {

    private static final Rubric CODING = RubricRegistry.forEvaluator(Evaluator.CODING);
    private static final EvaluatorConfidence SURE = new EvaluatorConfidence(Confidence.HIGH, "Clear code.");

    /** The same rating for every coding criterion. */
    private static Map<Criterion, Integer> all(int rating) {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        CODING.criteria().forEach(criterion -> ratings.put(criterion, rating));
        return ratings;
    }

    private static RatingRules.Evidence tested(TestEvidence tests) {
        return new RatingRules.Evidence(3, List.of(), 0, Set.of(), tests, true);
    }

    private static ScoredAnswer score(Map<Criterion, Integer> ratings, RatingRules.Evidence evidence) {
        return AnswerScorer.score(CODING, ratings, Map.of(), evidence, SURE);
    }

    private static CriterionScore criterion(ScoredAnswer answer, Criterion criterion) {
        return answer.criterionScores().stream().filter(score -> score.criterion() == criterion).findFirst().orElseThrow();
    }

    @Nested
    class CorrectnessFromTests {

        @ParameterizedTest(name = "{0} of {1} passed: {2}")
        @CsvSource({"10, 10, 4", "8, 10, 3", "9, 10, 3", "7, 10, 2", "5, 10, 2", "4, 10, 1", "1, 10, 1", "0, 10, 0", "4, 5, 3", "1, 2, 2"})
        void isThePassRateOfTheFunctionalTests(int passed, int total, int rating) {
            assertThat(new TestEvidence(true, passed, total, 0, 0, false).correctnessRating()).isEqualTo(rating);
        }

        @Test
        void isZeroWhenTheCodeDidNotCompile() {
            assertThat(new TestEvidence(false, 0, 9, 0, 3, false).correctnessRating()).isZero();
        }

        @Test
        void raisesTheModelsRatingWhenEveryTestPassed() {
            ScoredAnswer answer = score(all(2), tested(new TestEvidence(true, 9, 9, 3, 3, false)));

            CriterionScore correctness = criterion(answer, Criterion.CORRECTNESS);
            assertThat(correctness.rating()).isEqualTo(4);
            assertThat(correctness.modelRating()).isEqualTo(2);
            assertThat(correctness.setBy()).isEqualTo("TEST_RESULTS");
            assertThat(correctness.cappedBy()).isNull();
            assertThat(answer.measured()).containsExactly(correctness);
        }

        @Test
        void lowersTheModelsRatingWhenTestsFailed() {
            ScoredAnswer answer = score(all(4), tested(new TestEvidence(true, 3, 9, 0, 3, false)));

            assertThat(criterion(answer, Criterion.CORRECTNESS).rating()).isEqualTo(1);
            assertThat(criterion(answer, Criterion.CORRECTNESS).modelRating()).isEqualTo(4);
        }

        @Test
        void isNotLoweredAgainByTheFactualErrorsTheModelClaims() {
            RatingRules.Evidence evidence = new RatingRules.Evidence(3, List.of(), 3, Set.of(), new TestEvidence(true, 9, 9, 3, 3, false), true);

            assertThat(criterion(score(all(4), evidence), Criterion.CORRECTNESS).rating()).isEqualTo(4);
        }

        @Test
        void isNotLoweredByAnOffTopicOrInjectionFlagEither() {
            RatingRules.Evidence evidence = new RatingRules.Evidence(3, List.of(), 0,
                    Set.of(EvaluationFlag.OFF_TOPIC, EvaluationFlag.INJECTION_SUSPECTED), new TestEvidence(true, 9, 9, 3, 3, false), true);

            ScoredAnswer answer = score(all(4), evidence);

            assertThat(criterion(answer, Criterion.CORRECTNESS).rating()).isEqualTo(4);
            assertThat(criterion(answer, Criterion.READABILITY).rating()).isEqualTo(1);
        }

        @Test
        void counts35PercentOfTheScore() {
            ScoredAnswer allPass = score(all(0), tested(new TestEvidence(true, 9, 9, 0, 0, false)));

            assertThat(allPass.overall()).isEqualTo(35.0);
        }
    }

    @Nested
    class CapsFromTests {

        @Test
        void edgeCasesAreBoundedByTheEdgeCaseTestsThatPassed() {
            ScoredAnswer answer = score(all(4), tested(new TestEvidence(true, 7, 9, 1, 3, false)));

            CriterionScore edges = criterion(answer, Criterion.EDGE_CASES);
            assertThat(edges.rating()).isEqualTo(2);
            assertThat(edges.cappedBy()).isEqualTo("EDGE_CASE_TESTS");
        }

        @Test
        void allEdgeCasesPassedLeavesTheRatingAlone() {
            assertThat(criterion(score(all(3), tested(new TestEvidence(true, 9, 9, 3, 3, false))), Criterion.EDGE_CASES).capped()).isFalse();
        }

        @Test
        void aTimedOutPerformanceTestCapsTimeComplexityAtOne() {
            ScoredAnswer answer = score(all(4), tested(new TestEvidence(true, 9, 9, 3, 3, true)));

            assertThat(criterion(answer, Criterion.TIME_COMPLEXITY).rating()).isEqualTo(1);
            assertThat(criterion(answer, Criterion.TIME_COMPLEXITY).cappedBy()).isEqualTo("PERFORMANCE_TESTS");
            assertThat(criterion(answer, Criterion.SPACE_COMPLEXITY).rating()).isEqualTo(4);
        }

        @Test
        void anUntestedSolutionCannotBeRatedFullyCorrect() {
            RatingRules.Evidence untested = new RatingRules.Evidence(3, List.of(), 0, Set.of(), null, true);

            CriterionScore correctness = criterion(score(all(4), untested), Criterion.CORRECTNESS);

            assertThat(correctness.rating()).isEqualTo(3);
            assertThat(correctness.cappedBy()).isEqualTo("NO_TEST_EVIDENCE");
        }

        @Test
        void aFollowUpIsNotASolutionAndIsNotCapped() {
            RatingRules.Evidence followUp = new RatingRules.Evidence(3, List.of(), 0, Set.of(), null, false);

            assertThat(criterion(score(all(4), followUp), Criterion.CORRECTNESS).rating()).isEqualTo(4);
        }
    }

    @Nested
    class Confidence_ {

        @Test
        void isLoweredWhenTheTestsContradictTheModelByTwoPointsOrMore() {
            ScoredAnswer answer = score(all(4), tested(new TestEvidence(true, 2, 9, 3, 3, false)));

            assertThat(answer.confidence().level()).isEqualTo(Confidence.MEDIUM);
            assertThat(answer.confidence().reason()).contains("the tests disagreed");
        }

        @Test
        void staysWhenTheTestsAgreeWithTheModel() {
            ScoredAnswer answer = score(all(4), tested(new TestEvidence(true, 9, 9, 3, 3, false)));

            assertThat(answer.confidence()).isEqualTo(SURE);
        }
    }

    @Nested
    class Notes {

        @Test
        void explainARatingTheTestsSet() {
            TestEvidence tests = new TestEvidence(true, 7, 9, 2, 3, false);
            CriterionScore correctness = criterion(score(all(4), tested(tests)), Criterion.CORRECTNESS);

            assertThat(ScoreNotes.note(correctness, tests)).isEqualTo("Set by the tests: 7 of 9 functional tests passed. The reviewer's own rating was 4.");
        }

        @Test
        void explainACompilationFailure() {
            TestEvidence tests = new TestEvidence(false, 0, 9, 0, 3, false);
            CriterionScore correctness = criterion(score(all(3), tested(tests)), Criterion.CORRECTNESS);

            assertThat(ScoreNotes.note(correctness, tests)).contains("did not compile");
            assertThat(correctness.rating()).isZero();
        }

        @Test
        void explainEveryCapWithItsEvidence() {
            TestEvidence tests = new TestEvidence(true, 9, 9, 1, 3, true);
            ScoredAnswer answer = score(all(4), tested(tests));

            assertThat(ScoreNotes.note(criterion(answer, Criterion.EDGE_CASES), tests)).isEqualTo("Lowered from 4 to 2: 1 of 3 edge-case tests passed.");
            assertThat(ScoreNotes.note(criterion(answer, Criterion.TIME_COMPLEXITY), tests)).contains("Lowered from 4 to 1").contains("time limit");
            assertThat(ScoreNotes.note(criterion(answer, Criterion.READABILITY), tests)).isNull();
        }

        @Test
        void explainTheUnverifiedCap() {
            CriterionScore correctness = criterion(score(all(4), new RatingRules.Evidence(3, List.of(), 0, Set.of(), null, true)), Criterion.CORRECTNESS);

            assertThat(ScoreNotes.note(correctness, null)).contains("could not be run against tests");
        }
    }

    @Nested
    class StoredScores {

        @Test
        void aMeasuredRatingMayBeAboveTheModelsButACappedOneNever() {
            assertThat(CriterionScore.measured(Criterion.CORRECTNESS, 4, 0.35, "", 1, "TEST_RESULTS").measured()).isTrue();
            assertThatThrownBy(() -> new CriterionScore(Criterion.CORRECTNESS, 4, 0.35, "", 1, "FACTUAL_ERRORS"))
                    .hasMessageContaining("only lowers");
            assertThatThrownBy(() -> new CriterionScore(Criterion.CORRECTNESS, 4, 0.35, "", 1, "FACTUAL_ERRORS", "TEST_RESULTS"))
                    .hasMessageContaining("either lowered");
            assertThatThrownBy(() -> new CriterionScore(Criterion.CORRECTNESS, 4, 0.35, "", null, null, "TEST_RESULTS"))
                    .hasMessageContaining("names both");
        }

        @Test
        void evidenceIsConsistent() {
            assertThatThrownBy(() -> new TestEvidence(false, 1, 9, 0, 0, false)).hasMessageContaining("did not compile");
            assertThatThrownBy(() -> new TestEvidence(true, 10, 9, 0, 0, false)).hasMessageContaining("functionalPassed");
            assertThat(new TestEvidence(true, 1, 1, 0, 0, false).edgeCaseCap()).isNull();
        }
    }
}
