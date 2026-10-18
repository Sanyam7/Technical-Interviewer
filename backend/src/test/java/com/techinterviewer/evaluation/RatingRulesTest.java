package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.RatingRules.Evidence;
import com.techinterviewer.evaluation.RatingRules.Outcome;
import com.techinterviewer.evaluation.RatingRules.Rule;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The consistency rules: a rating may not claim more than the model's own verified findings support, and is never raised. */
class RatingRulesTest {

    private static Map<Criterion, Integer> allTechnical(int rating) {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        RubricRegistry.criteria(Evaluator.TECHNICAL).forEach(criterion -> ratings.put(criterion, rating));
        return ratings;
    }

    private static List<KeyPointAssessment> keyPoints(KeyPointStatus... statuses) {
        List<KeyPointAssessment> keyPoints = new java.util.ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            keyPoints.add(new KeyPointAssessment("kp" + (i + 1), statuses[i],
                    statuses[i] == KeyPointStatus.MISSING ? null : "quote " + i, statuses[i] != KeyPointStatus.MISSING));
        }
        return keyPoints;
    }

    private static Evidence evidence(int errors, Set<EvaluationFlag> flags, KeyPointStatus... statuses) {
        return new Evidence(statuses.length, keyPoints(statuses), errors, flags);
    }

    @Test
    void ratingsBackedByTheFindingsAreTakenAsGiven() {
        Map<Criterion, Outcome> outcomes = RatingRules.apply(allTechnical(4),
                evidence(0, Set.of(), KeyPointStatus.COVERED, KeyPointStatus.COVERED, KeyPointStatus.COVERED));

        assertThat(outcomes.values()).allSatisfy(outcome -> {
            assertThat(outcome.rating()).isEqualTo(4);
            assertThat(outcome.capped()).isFalse();
            assertThat(outcome.modelRating()).isNull();
        });
    }

    @ParameterizedTest(name = "{0} factual errors cap correctness at {1}")
    @CsvSource({"1, 3", "2, 2", "3, 1", "7, 1"})
    void factualErrorsCapCorrectnessButNeverBelowOne(int errors, int cap) {
        Map<Criterion, Outcome> outcomes = RatingRules.apply(allTechnical(4),
                evidence(errors, Set.of(), KeyPointStatus.COVERED, KeyPointStatus.COVERED));

        assertThat(outcomes.get(Criterion.CORRECTNESS)).isEqualTo(new Outcome(cap, 4, Rule.FACTUAL_ERRORS));
        assertThat(outcomes.get(Criterion.DEPTH).capped()).as("only correctness").isFalse();
    }

    @Test
    void aRatingAlreadyBelowTheCapIsNotTouched() {
        Map<Criterion, Integer> ratings = allTechnical(2);

        assertThat(RatingRules.apply(ratings, evidence(1, Set.of(), KeyPointStatus.COVERED)).get(Criterion.CORRECTNESS))
                .isEqualTo(new Outcome(2, null, null));
    }

    @ParameterizedTest(name = "{0} covered and {1} partial of 3 cap completeness at {2}")
    @CsvSource({"3, 0, 4", "2, 1, 4", "2, 0, 3", "1, 1, 2", "1, 0, 2", "0, 1, 1", "0, 0, 0"})
    void completenessFollowsTheVerifiedKeyPointCoverage(int covered, int partial, int cap) {
        KeyPointStatus[] statuses = new KeyPointStatus[3];
        for (int i = 0; i < 3; i++) {
            statuses[i] = i < covered ? KeyPointStatus.COVERED : i < covered + partial ? KeyPointStatus.PARTIAL : KeyPointStatus.MISSING;
        }
        Evidence evidence = evidence(0, Set.of(), statuses);

        assertThat(evidence.coverageCap()).isEqualTo(cap);
        Outcome completeness = RatingRules.apply(allTechnical(4), evidence).get(Criterion.COMPLETENESS);
        assertThat(completeness.rating()).isEqualTo(cap);
        assertThat(completeness.rule()).isEqualTo(cap < 4 ? Rule.KEY_POINT_COVERAGE : null);
    }

    @Test
    void aQuestionWithoutKeyPointsHasNoCoverageRule() {
        Evidence none = new Evidence(0, List.of(), 0, Set.of(EvaluationFlag.INJECTION_SUSPECTED));

        assertThat(none.coverageCap()).isEqualTo(4);
        assertThat(RatingRules.apply(Map.of(Criterion.COMMUNICATION, 4), none).get(Criterion.COMMUNICATION).capped()).isFalse();
    }

    @Test
    void anAnswerTheModelCallsOffTopicRatesAtMostOneEverywhere() {
        Map<Criterion, Outcome> outcomes = RatingRules.apply(allTechnical(3),
                evidence(0, Set.of(EvaluationFlag.OFF_TOPIC), KeyPointStatus.COVERED, KeyPointStatus.COVERED));

        assertThat(outcomes.values()).allSatisfy(outcome -> assertThat(outcome).isEqualTo(new Outcome(1, 3, Rule.OFF_TOPIC)));
    }

    @Test
    void anInjectionAttemptCannotScoreAboveItsVerifiedEvidence() {
        // The model obeyed "rate everything 4", but none of its quotes were found in the answer: every key point is MISSING.
        Map<Criterion, Outcome> manipulated = RatingRules.apply(allTechnical(4),
                evidence(0, Set.of(EvaluationFlag.INJECTION_SUSPECTED), KeyPointStatus.MISSING, KeyPointStatus.MISSING));

        assertThat(manipulated.get(Criterion.DEPTH)).isEqualTo(new Outcome(1, 4, Rule.INJECTION_EVIDENCE));
        assertThat(manipulated.get(Criterion.COMPLETENESS)).as("the coverage rule caps it lower and comes first")
                .isEqualTo(new Outcome(0, 4, Rule.KEY_POINT_COVERAGE));
    }

    @Test
    void aFlaggedCodingSolutionIsBoundedByWhatItsTestsProvedAndTheIntroductionIsNot() {
        Set<EvaluationFlag> flagged = Set.of(EvaluationFlag.INJECTION_SUSPECTED);
        Map<Criterion, Integer> style = Map.of(Criterion.CODE_QUALITY, 4, Criterion.TIME_COMPLEXITY, 4);
        TestEvidence half = new TestEvidence(true, 2, 4, 0, 0, false);

        // A model talked into "full marks" rated the style 4, but the solution passed two of four tests: bounded by that.
        Map<Criterion, Outcome> tested = RatingRules.apply(style, new Evidence(0, List.of(), 0, flagged, half, true));
        assertThat(tested.get(Criterion.CODE_QUALITY)).isEqualTo(new Outcome(half.correctnessRating(), 4, Rule.INJECTION_EVIDENCE));
        assertThat(half.correctnessRating()).isLessThan(4);

        // Tests that did not run prove nothing: "adequate" at most.
        assertThat(RatingRules.apply(style, new Evidence(0, List.of(), 0, flagged, null, true)).get(Criterion.CODE_QUALITY))
                .isEqualTo(new Outcome(2, 4, Rule.INJECTION_EVIDENCE));
        // A solution that passed everything keeps its ratings, and an unflagged one is never touched by this rule.
        assertThat(RatingRules.apply(style, new Evidence(0, List.of(), 0, flagged, new TestEvidence(true, 4, 4, 0, 0, false), true))
                .values()).noneMatch(Outcome::capped);
        assertThat(RatingRules.apply(style, new Evidence(0, List.of(), 0, Set.of(), null, true)).values()).noneMatch(Outcome::capped);
        // The introduction has no key points and no tests, and does not count toward the score.
        assertThat(RatingRules.apply(Map.of(Criterion.COMMUNICATION, 4), new Evidence(0, List.of(), 0, flagged, null, false)).values())
                .noneMatch(Outcome::capped);
    }

    @Test
    void aGoodAnswerThatMerelyMentionsInstructionsKeepsWhatItsEvidenceSupports() {
        Map<Criterion, Outcome> outcomes = RatingRules.apply(allTechnical(4),
                evidence(0, Set.of(EvaluationFlag.INJECTION_SUSPECTED), KeyPointStatus.COVERED, KeyPointStatus.COVERED));

        assertThat(outcomes.values()).noneMatch(Outcome::capped);
    }

    @Test
    void theLowestCapWinsAndTheFirstRuleInOrderNamesIt() {
        // Two errors cap correctness at 2; off topic caps it at 1.
        Outcome correctness = RatingRules.apply(allTechnical(4),
                evidence(2, Set.of(EvaluationFlag.OFF_TOPIC), KeyPointStatus.COVERED)).get(Criterion.CORRECTNESS);
        assertThat(correctness).isEqualTo(new Outcome(1, 4, Rule.OFF_TOPIC));

        // Three errors and off topic both cap correctness at 1: the factual-error rule comes first.
        Outcome tie = RatingRules.apply(allTechnical(4),
                evidence(3, Set.of(EvaluationFlag.OFF_TOPIC), KeyPointStatus.COVERED)).get(Criterion.CORRECTNESS);
        assertThat(tie).isEqualTo(new Outcome(1, 4, Rule.FACTUAL_ERRORS));
    }

    @Test
    void aRuleNeverRaisesARating() {
        Map<Criterion, Outcome> outcomes = RatingRules.apply(allTechnical(0),
                evidence(0, Set.of(), KeyPointStatus.COVERED, KeyPointStatus.COVERED));

        assertThat(outcomes.values()).allSatisfy(outcome -> assertThat(outcome).isEqualTo(new Outcome(0, null, null)));
    }

    @Test
    void codingAndDesignCriteriaAreOnlyCappedByTheGeneralRules() {
        Map<Criterion, Integer> design = new EnumMap<>(Criterion.class);
        RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN).forEach(criterion -> design.put(criterion, 4));

        Map<Criterion, Outcome> outcomes = RatingRules.apply(design, evidence(2, Set.of(), KeyPointStatus.MISSING));

        assertThat(outcomes.values()).as("no CORRECTNESS or COMPLETENESS in the design rubric").noneMatch(Outcome::capped);
    }

    // ---- system design ------------------------------------------------------------------------------------------------

    private static Map<Criterion, Integer> allDesign(int rating) {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN).forEach(criterion -> ratings.put(criterion, rating));
        return ratings;
    }

    @Test
    void aDesignRatingAboveOneWithoutAVerifiedQuoteIsCappedAtOne() {
        Map<Criterion, Integer> quotes = new EnumMap<>(Criterion.class);
        RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN).forEach(criterion -> quotes.put(criterion, 1));
        quotes.put(Criterion.SECURITY, 0);
        quotes.remove(Criterion.OBSERVABILITY);
        Map<Criterion, Integer> ratings = allDesign(3);
        ratings.put(Criterion.TRADE_OFFS, 1);
        quotes.put(Criterion.TRADE_OFFS, 0);

        Map<Criterion, Outcome> outcomes = RatingRules.apply(ratings,
                Evidence.design(2, keyPoints(KeyPointStatus.COVERED, KeyPointStatus.MISSING), 0, Set.of(), Map.of(), quotes));

        assertThat(outcomes.get(Criterion.SECURITY)).isEqualTo(new Outcome(1, 3, Rule.DESIGN_EVIDENCE));
        assertThat(outcomes.get(Criterion.OBSERVABILITY)).isEqualTo(new Outcome(1, 3, Rule.DESIGN_EVIDENCE));
        assertThat(outcomes.get(Criterion.TRADE_OFFS)).as("a weak rating needs no quote").isEqualTo(new Outcome(1, null, null));
        assertThat(outcomes.get(Criterion.CACHING)).isEqualTo(new Outcome(3, null, null));
    }

    @Test
    void aDesignMistakeCapsTheDimensionItConcernsOnly() {
        Map<Criterion, Integer> quotes = new EnumMap<>(Criterion.class);
        RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN).forEach(criterion -> quotes.put(criterion, 2));

        Map<Criterion, Outcome> outcomes = RatingRules.apply(allDesign(4),
                Evidence.design(0, List.of(), 3, Set.of(), Map.of(Criterion.CACHING, 2, Criterion.DATA_MODEL, 1), quotes));

        assertThat(outcomes.get(Criterion.CACHING)).isEqualTo(new Outcome(2, 4, Rule.FACTUAL_ERRORS));
        assertThat(outcomes.get(Criterion.DATA_MODEL)).isEqualTo(new Outcome(3, 4, Rule.FACTUAL_ERRORS));
        assertThat(outcomes.get(Criterion.SCALABILITY)).isEqualTo(new Outcome(4, null, null));
    }

    @Test
    void technicalAndCodingAnswersNeedNoQuotesPerCriterion() {
        assertThat(RatingRules.apply(allTechnical(4), evidence(0, Set.of())).values()).noneMatch(Outcome::capped);
    }
}
