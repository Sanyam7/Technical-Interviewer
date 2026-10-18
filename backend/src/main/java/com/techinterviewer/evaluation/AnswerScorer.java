package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns the evaluator's component assessments into the stored result of one answer, deterministically:
 *
 * <ol>
 *   <li>{@link RatingRules} lower ratings that contradict the model's own verified findings or the test results, and
 *       let the tests decide the correctness of a coding answer;
 *   <li>{@link ScoreAggregator} computes {@code overall = sum(weight * rating / 4) * 100} with the rubric the
 *       {@link ScoringPolicy} chose for the question;
 *   <li>{@link ConfidencePolicy} adjusts the stated confidence, and a LOW one flags the answer for review.
 * </ol>
 *
 * The same code scores answers in the application and in the evaluation harness ({@code ai-evals}), so a measured
 * score is exactly the score a candidate would get.
 */
public final class AnswerScorer {

    /**
     * The scored answer.
     *
     * @param overall         0 to 100, two decimals
     * @param criterionScores one per rubric criterion, in rubric order, with the weight applied and, for a lowered rating,
     *                        the model's rating and the rule
     * @param confidence      the evaluator's confidence after the server's adjustment; null when it stated none
     * @param flags           the evaluation's flags, plus {@code LOW_EVALUATOR_CONFIDENCE} when the confidence ended LOW
     */
    public record ScoredAnswer(double overall, List<CriterionScore> criterionScores, @Nullable EvaluatorConfidence confidence,
            Set<EvaluationFlag> flags) {

        public ScoredAnswer {
            Require.between(overall, 0, 100, "overall");
            criterionScores = Immutables.list(criterionScores);
            flags = Immutables.enumSet(flags, EvaluationFlag.class);
        }

        /** The ratings the rules lowered. */
        public List<CriterionScore> capped() {
            return criterionScores.stream().filter(CriterionScore::capped).toList();
        }

        /** The ratings a measurement decided. */
        public List<CriterionScore> measured() {
            return criterionScores.stream().filter(CriterionScore::measured).toList();
        }
    }

    private AnswerScorer() {
    }

    /**
     * @param rubric     the criteria and weights to score with
     * @param ratings    the model's rating, 0 to 4, per rubric criterion
     * @param rationales the model's reason per rated criterion
     * @param evidence   the verified key points, the factual errors and the flags
     * @param stated     the model's confidence, null when it stated none
     * @throws IllegalArgumentException when the ratings are not exactly the rubric's criteria or out of range
     */
    public static ScoredAnswer score(Rubric rubric, Map<Criterion, Integer> ratings, Map<Criterion, String> rationales,
            RatingRules.Evidence evidence, @Nullable EvaluatorConfidence stated) {
        return score(rubric, ratings, rationales, Map.of(), evidence, stated);
    }

    /**
     * @param quotes the verified words of the candidate each rating rests on (system design), stored with the criterion
     * @see #score(Rubric, Map, Map, RatingRules.Evidence, EvaluatorConfidence)
     */
    public static ScoredAnswer score(Rubric rubric, Map<Criterion, Integer> ratings, Map<Criterion, String> rationales,
            Map<Criterion, List<String>> quotes, RatingRules.Evidence evidence, @Nullable EvaluatorConfidence stated) {
        Require.nonNull(rubric, "rubric");
        Require.nonNull(ratings, "ratings");
        ratings.forEach((criterion, rating) -> Require.between(Require.nonNull(rating, "rating of " + criterion),
                CriterionScore.MIN_RATING, CriterionScore.MAX_RATING, "rating of " + criterion));
        Map<Criterion, RatingRules.Outcome> outcomes = RatingRules.apply(ratings, evidence);
        Map<Criterion, Integer> scored = new EnumMap<>(Criterion.class);
        outcomes.forEach((criterion, outcome) -> scored.put(criterion, outcome.rating()));
        AggregatedScore aggregated = ScoreAggregator.aggregate(rubric, scored, rationales);

        List<CriterionScore> criterionScores = new ArrayList<>();
        boolean anyCapped = false;
        boolean testsDisagree = false;
        for (CriterionScore aggregatedScore : aggregated.criterionScores()) {
            CriterionScore score = aggregatedScore.withEvidence(quotes.getOrDefault(aggregatedScore.criterion(), List.of()));
            RatingRules.Outcome outcome = outcomes.get(score.criterion());
            if (outcome.measured()) {
                testsDisagree |= Math.abs(outcome.modelRating() - outcome.rating()) >= 2;
                criterionScores.add(CriterionScore.measured(score.criterion(), score.rating(), score.weight(), score.rationale(),
                        outcome.modelRating(), outcome.rule().name()).withEvidence(score.evidence()));
            } else if (outcome.capped()) {
                anyCapped = true;
                criterionScores.add(new CriterionScore(score.criterion(), score.rating(), score.weight(), score.rationale(),
                        outcome.modelRating(), outcome.rule().name(), null, score.evidence()));
            } else {
                criterionScores.add(score);
            }
        }
        Set<EvaluationFlag> flags = evidence.flags().isEmpty() ? EnumSet.noneOf(EvaluationFlag.class) : EnumSet.copyOf(evidence.flags());
        EvaluatorConfidence confidence = ConfidencePolicy.adjust(stated, flags, anyCapped, testsDisagree);
        if (confidence != null && confidence.level() == Confidence.LOW) {
            flags.add(EvaluationFlag.LOW_EVALUATOR_CONFIDENCE);
        }
        return new ScoredAnswer(aggregated.overall(), criterionScores, confidence, flags);
    }
}
