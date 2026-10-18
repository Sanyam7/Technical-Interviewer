package com.techinterviewer.evaluation;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only place an answer's score is computed: {@code overall = sum(weight * rating / 4) * 100}, from criterion
 * ratings of 0 to 4 and the rubric's weights, rounded to two decimals half up. A model never supplies a score.
 *
 * <p>The ratings must be exactly the criteria of the evaluator's rubric: a missing or an extra criterion is an error,
 * not something to guess around.
 */
public final class ScoreAggregator {

    private static final BigDecimal POINTS_PER_RATING = BigDecimal.valueOf(100).divide(BigDecimal.valueOf(CriterionScore.MAX_RATING));

    private ScoreAggregator() {
    }

    /** Scores an answer with the registered rubric of {@code evaluator}. */
    public static AggregatedScore aggregate(Evaluator evaluator, Map<Criterion, Integer> ratings, Map<Criterion, String> rationales) {
        return aggregate(RubricRegistry.forEvaluator(evaluator), ratings, rationales);
    }

    /**
     * @param ratings    rating 0 to 4 per criterion
     * @param rationales the evaluator's short justification per criterion; a criterion without one gets an empty text,
     *                   a rationale for an unrated criterion is an error
     * @throws IllegalArgumentException when a rating is outside 0 to 4, or criteria are missing or extra
     */
    public static AggregatedScore aggregate(Rubric rubric, Map<Criterion, Integer> ratings, Map<Criterion, String> rationales) {
        Require.nonNull(rubric, "rubric");
        Require.nonNull(ratings, "ratings");
        Require.nonNull(rationales, "rationales");
        requireExactCriteria(rubric, ratings.keySet(), "ratings");
        if (!ratings.keySet().containsAll(rationales.keySet())) {
            throw new IllegalArgumentException("rationales for unrated criteria " + difference(rationales.keySet(), ratings.keySet()));
        }
        BigDecimal weightedRatings = BigDecimal.ZERO;
        List<CriterionScore> scores = new ArrayList<>();
        for (CriterionWeight weight : rubric.weights()) {
            int rating = Require.between(Require.nonNull(ratings.get(weight.criterion()), "rating of " + weight.criterion()),
                    CriterionScore.MIN_RATING, CriterionScore.MAX_RATING, "rating of " + weight.criterion());
            weightedRatings = weightedRatings.add(weight.weight().multiply(BigDecimal.valueOf(rating)));
            scores.add(new CriterionScore(weight.criterion(), rating, weight.weight().doubleValue(),
                    rationales.getOrDefault(weight.criterion(), "")));
        }
        double overall = weightedRatings.multiply(POINTS_PER_RATING).setScale(2, RoundingMode.HALF_UP).doubleValue();
        return new AggregatedScore(overall, scores);
    }

    private static void requireExactCriteria(Rubric rubric, Set<Criterion> given, String what) {
        Set<Criterion> expected = rubric.criteria();
        if (!given.equals(expected)) {
            throw new IllegalArgumentException(what + " must rate exactly " + expected + " (rubric " + rubric.id() + "): missing "
                    + difference(expected, given) + ", unexpected " + difference(given, expected));
        }
    }

    private static Set<Criterion> difference(Set<Criterion> left, Set<Criterion> right) {
        Set<Criterion> result = EnumSet.noneOf(Criterion.class);
        result.addAll(left);
        result.removeAll(right);
        return result;
    }
}
