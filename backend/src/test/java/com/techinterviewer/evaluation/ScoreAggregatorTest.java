package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScoreAggregatorTest {

    /** Ratings of the technical rubric, in its order. */
    private static Map<Criterion, Integer> technical(int correctness, int completeness, int depth, int practical, int reasoning, int communication) {
        return Map.of(Criterion.CORRECTNESS, correctness, Criterion.COMPLETENESS, completeness, Criterion.DEPTH, depth,
                Criterion.PRACTICAL_UNDERSTANDING, practical, Criterion.REASONING, reasoning, Criterion.COMMUNICATION, communication);
    }

    private static Map<Criterion, Integer> uniform(Evaluator evaluator, int rating) {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        RubricRegistry.forEvaluator(evaluator).criteria().forEach(criterion -> ratings.put(criterion, rating));
        return ratings;
    }

    @Test
    void documentExampleScoresSixtyThreePointSevenFive() {
        // 0.30*3 + 0.10*2 + 0.20*2 + 0.15*2 + 0.15*3 + 0.10*3 = 2.55 of 4 = 63.75
        AggregatedScore score = ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(3, 2, 2, 2, 3, 3), Map.of());

        assertThat(score.overall()).isEqualTo(63.75);
    }

    @ParameterizedTest
    @EnumSource(Evaluator.class)
    void everyRatingAtTheMaximumScoresOneHundredAndAtTheMinimumZero(Evaluator evaluator) {
        assertThat(ScoreAggregator.aggregate(evaluator, uniform(evaluator, 4), Map.of()).overall()).isEqualTo(100.0);
        assertThat(ScoreAggregator.aggregate(evaluator, uniform(evaluator, 0), Map.of()).overall()).isEqualTo(0.0);
        assertThat(ScoreAggregator.aggregate(evaluator, uniform(evaluator, 2), Map.of()).overall()).isEqualTo(50.0);
    }

    @Test
    void eachTechnicalCriterionContributesItsOwnWeight() {
        assertThat(ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(4, 0, 0, 0, 0, 0), Map.of()).overall()).isEqualTo(30.0);
        assertThat(ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(0, 4, 0, 0, 0, 0), Map.of()).overall()).isEqualTo(10.0);
        assertThat(ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(0, 0, 4, 0, 0, 0), Map.of()).overall()).isEqualTo(20.0);
        assertThat(ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(0, 0, 0, 4, 0, 0), Map.of()).overall()).isEqualTo(15.0);
        assertThat(ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(0, 0, 0, 0, 4, 0), Map.of()).overall()).isEqualTo(15.0);
        assertThat(ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(0, 0, 0, 0, 0, 4), Map.of()).overall()).isEqualTo(10.0);
    }

    @Test
    void eachCodingAndDesignCriterionContributesItsOwnWeight() {
        Map<Criterion, Double> expected = Map.ofEntries(
                Map.entry(Criterion.CORRECTNESS, 35.0), Map.entry(Criterion.ALGORITHM, 15.0),
                Map.entry(Criterion.TIME_COMPLEXITY, 15.0), Map.entry(Criterion.SPACE_COMPLEXITY, 5.0),
                Map.entry(Criterion.EDGE_CASES, 10.0), Map.entry(Criterion.READABILITY, 10.0),
                Map.entry(Criterion.MAINTAINABILITY, 10.0));
        expected.forEach((criterion, points) -> {
            Map<Criterion, Integer> ratings = uniform(Evaluator.CODING, 0);
            ratings.put(criterion, 4);
            assertThat(ScoreAggregator.aggregate(Evaluator.CODING, ratings, Map.of()).overall()).as(criterion.name()).isEqualTo(points);
        });
        Map<Criterion, Double> design = Map.ofEntries(
                Map.entry(Criterion.REQUIREMENTS, 10.0), Map.entry(Criterion.HIGH_LEVEL_DESIGN, 12.0),
                Map.entry(Criterion.API_DESIGN, 6.0), Map.entry(Criterion.DATA_MODEL, 10.0),
                Map.entry(Criterion.CACHING, 7.0), Map.entry(Criterion.MESSAGING, 6.0),
                Map.entry(Criterion.SCALABILITY, 12.0), Map.entry(Criterion.AVAILABILITY, 7.0),
                Map.entry(Criterion.RELIABILITY, 7.0), Map.entry(Criterion.FAILURE_HANDLING, 7.0),
                Map.entry(Criterion.SECURITY, 5.0), Map.entry(Criterion.OBSERVABILITY, 4.0),
                Map.entry(Criterion.TRADE_OFFS, 7.0));
        design.forEach((criterion, points) -> {
            Map<Criterion, Integer> ratings = uniform(Evaluator.SYSTEM_DESIGN, 0);
            ratings.put(criterion, 4);
            assertThat(ScoreAggregator.aggregate(Evaluator.SYSTEM_DESIGN, ratings, Map.of()).overall()).as(criterion.name()).isEqualTo(points);
        });
    }

    @Test
    void introductionIsScoredOnCommunicationAlone() {
        assertThat(ScoreAggregator.aggregate(Evaluator.INTRO, Map.of(Criterion.COMMUNICATION, 3), Map.of()).overall()).isEqualTo(75.0);
    }

    @Test
    void roundsToTwoDecimalsHalfUp() {
        Rubric thirds = new Rubric("thirds.v1", Evaluator.TECHNICAL, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.333"),
                CriterionWeight.of(Criterion.DEPTH, "0.333"),
                CriterionWeight.of(Criterion.COMPLETENESS, "0.334")));

        // 0.333 * 1 / 4 * 100 = 8.325 exactly: half up gives 8.33 (half even would give 8.32)
        double up = ScoreAggregator.aggregate(thirds,
                Map.of(Criterion.CORRECTNESS, 1, Criterion.DEPTH, 0, Criterion.COMPLETENESS, 0), Map.of()).overall();
        // 0.334 * 3 / 4 * 100 = 25.05
        double exact = ScoreAggregator.aggregate(thirds,
                Map.of(Criterion.CORRECTNESS, 0, Criterion.DEPTH, 0, Criterion.COMPLETENESS, 3), Map.of()).overall();
        // 0.333 * 1 + 0.333 * 1 + 0.334 * 1 = 1 of 4 = 25 exactly
        double ones = ScoreAggregator.aggregate(thirds,
                Map.of(Criterion.CORRECTNESS, 1, Criterion.DEPTH, 1, Criterion.COMPLETENESS, 1), Map.of()).overall();

        assertThat(up).isEqualTo(8.33);
        assertThat(exact).isEqualTo(25.05);
        assertThat(ones).isEqualTo(25.0);
    }

    @Test
    void roundingNeverDropsBelowHalfUpEvenForTinyFractions() {
        Rubric rubric = new Rubric("odd.v1", Evaluator.TECHNICAL, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.333"),
                CriterionWeight.of(Criterion.DEPTH, "0.667")));

        // 0.667 * 1 / 4 * 100 = 16.675 -> 16.68
        assertThat(ScoreAggregator.aggregate(rubric, Map.of(Criterion.CORRECTNESS, 0, Criterion.DEPTH, 1), Map.of()).overall())
                .isEqualTo(16.68);
    }

    @Test
    void criterionScoresCarryRatingWeightRationaleInRubricOrder() {
        AggregatedScore score = ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(3, 1, 2, 2, 3, 4),
                Map.of(Criterion.CORRECTNESS, "Right on visibility", Criterion.COMMUNICATION, "Clear"));

        assertThat(score.criterionScores()).containsExactly(
                new CriterionScore(Criterion.CORRECTNESS, 3, 0.30, "Right on visibility"),
                new CriterionScore(Criterion.COMPLETENESS, 1, 0.10, ""),
                new CriterionScore(Criterion.DEPTH, 2, 0.20, ""),
                new CriterionScore(Criterion.PRACTICAL_UNDERSTANDING, 2, 0.15, ""),
                new CriterionScore(Criterion.REASONING, 3, 0.15, ""),
                new CriterionScore(Criterion.COMMUNICATION, 4, 0.10, "Clear"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 5, 100, Integer.MIN_VALUE})
    void ratingsOutsideZeroToFourAreRejected(int bad) {
        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.TECHNICAL, technical(bad, 2, 2, 2, 2, 2), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CORRECTNESS");
    }

    @Test
    void aMissingCriterionIsRejectedAndNamed() {
        Map<Criterion, Integer> missing = Map.of(Criterion.CORRECTNESS, 3, Criterion.DEPTH, 2, Criterion.COMPLETENESS, 2);

        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.TECHNICAL, missing, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing [COMMUNICATION, PRACTICAL_UNDERSTANDING, REASONING]");
    }

    @Test
    void anExtraCriterionIsRejectedAndNamed() {
        Map<Criterion, Integer> extra = new EnumMap<>(technical(3, 2, 2, 2, 2, 2));
        extra.put(Criterion.EDGE_CASES, 4);

        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.TECHNICAL, extra, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected [EDGE_CASES]");
    }

    @Test
    void criteriaOfAnotherRubricAreRejected() {
        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.CODING, technical(3, 2, 2, 2, 2, 2), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.INTRO, technical(3, 2, 2, 2, 2, 2), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRationaleForAnUnratedCriterionIsRejected() {
        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.INTRO, Map.of(Criterion.COMMUNICATION, 3),
                Map.of(Criterion.DEPTH, "stray")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEPTH");
    }

    @Test
    void nullInputsAreRejected() {
        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.INTRO, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreAggregator.aggregate(Evaluator.INTRO, Map.of(Criterion.COMMUNICATION, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreAggregator.aggregate((Rubric) null, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregatedScoreRejectsAnOverallOutsideZeroToOneHundred() {
        assertThatThrownBy(() -> new AggregatedScore(100.01, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregatedScore(-0.01, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AggregatedScore(100, List.of()).overall()).isEqualTo(100.0);
    }
}
