package com.techinterviewer.evaluation;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Consistency rules between the model's ratings and its own verified findings (AI_ARCHITECTURE.md section 2.10). The
 * model rates each criterion and, separately, judges key points (with quotes the server verified) and lists factual
 * errors. When a rating claims more than those findings support, the server lowers it before the weighted sum:
 *
 * <ol>
 *   <li>{@link Rule#FACTUAL_ERRORS}: CORRECTNESS is at most {@code 4 - errors}, never below 1 (one wrong statement
 *       caps it at "strong", three at "weak"). A system design has no correctness criterion: there a factual error names the
 *       dimension it concerns, and that dimension is capped the same way by its own errors.
 *   <li>{@link Rule#KEY_POINT_COVERAGE}: COMPLETENESS is at most {@code ceil(4 * coverage)}, where coverage counts a
 *       covered key point as 1 and a partly covered one as 1/2, over the question's expected key points.
 *   <li>{@link Rule#OFF_TOPIC}: an answer the model itself calls off topic rates at most 1 on every criterion.
 *   <li>{@link Rule#INJECTION_EVIDENCE}: when the answer tried to instruct the evaluator, no criterion rates above what
 *       its verified key-point evidence supports ({@code max(1, ceil(4 * coverage))}); for a coding solution, above what its
 *       tests proved (at least 1), or 2 when they did not run. Instructions cannot raise a rating, and a manipulated rating
 *       is bounded by evidence the server checked.
 *   <li>{@link Rule#DESIGN_EVIDENCE}: a system design dimension rated above 1 is capped at 1 when the evaluator quoted none of
 *       the candidate's words for it that the server could find (in the thread's answers or diagram). A rating of a design must
 *       rest on what the candidate wrote, not on what the model assumes the candidate meant.
 *   <li>{@link Rule#EDGE_CASE_TESTS}: EDGE_CASES is at most {@code ceil(4 * passed / total)} of the edge-case tests.
 *   <li>{@link Rule#PERFORMANCE_TESTS}: TIME_COMPLEXITY is at most 1 when a performance test exceeded its time limit.
 *   <li>{@link Rule#NO_TEST_EVIDENCE}: a solution to a coding problem whose tests did not run (a generated problem, an
 *       unavailable sandbox, a language without a harness) rates at most 3 on CORRECTNESS: unverified code cannot be
 *       judged fully correct by a model.
 * </ol>
 *
 * These rules only ever lower a rating. One measurement replaces a rating instead: {@link Rule#TEST_RESULTS}. When the
 * tests of a coding answer ran, CORRECTNESS is the test result ({@link TestEvidence#correctnessRating()}), above or below
 * the model's rating, and no other rule touches it. The key-point rules apply only to questions with expected key points.
 * When several rules cap a rating, the lowest cap wins and the first rule in this order names it.
 */
public final class RatingRules {

    /**
     * Why a rating was lowered, stored by name in {@code criterion_scores[].cappedBy}; or, for {@link #TEST_RESULTS}, what decided
     * it, stored in {@code criterion_scores[].setBy}.
     */
    public enum Rule {
        FACTUAL_ERRORS,
        KEY_POINT_COVERAGE,
        OFF_TOPIC,
        INJECTION_EVIDENCE,
        DESIGN_EVIDENCE,
        EDGE_CASE_TESTS,
        PERFORMANCE_TESTS,
        NO_TEST_EVIDENCE,
        /** Not a cap: the tests decide CORRECTNESS. */
        TEST_RESULTS;

        /** Whether the rule decides the rating instead of capping it. */
        public boolean measurement() {
            return this == TEST_RESULTS;
        }
    }

    /**
     * What the model found besides its ratings, after the server's verification.
     *
     * @param expectedKeyPoints how many key points the question expects (0 for the introduction)
     * @param keyPoints         the verified verdicts (a quote that is not in the answer was already downgraded to MISSING)
     * @param factualErrors     how many wrong statements the evaluation lists
     * @param flags             the evaluation's flags, from the model and the server's heuristics
     * @param tests             what the tests of a coding answer showed; null when none ran
     * @param testsExpected     the answer is a solution to a coding problem, which tests should confirm
     * @param errorsByCriterion how many factual errors concern each system design dimension; empty for other answers
     * @param quotesByCriterion how many verified quotes of the candidate back each rating, for a rubric whose ratings must rest on
     *                          quotes (system design); null when the rubric asks for none
     */
    public record Evidence(int expectedKeyPoints, List<KeyPointAssessment> keyPoints, int factualErrors, Set<EvaluationFlag> flags,
            @Nullable TestEvidence tests, boolean testsExpected, Map<Criterion, Integer> errorsByCriterion,
            @Nullable Map<Criterion, Integer> quotesByCriterion) {

        public Evidence {
            Require.atLeast(expectedKeyPoints, 0, "expectedKeyPoints");
            keyPoints = List.copyOf(keyPoints);
            Require.atLeast(factualErrors, 0, "factualErrors");
            flags = flags.isEmpty() ? EnumSet.noneOf(EvaluationFlag.class) : EnumSet.copyOf(flags);
            errorsByCriterion = Map.copyOf(errorsByCriterion);
            quotesByCriterion = quotesByCriterion == null ? null : Map.copyOf(quotesByCriterion);
        }

        /** The evidence of an answer whose ratings need no quotes and whose errors name no dimension. */
        public Evidence(int expectedKeyPoints, List<KeyPointAssessment> keyPoints, int factualErrors, Set<EvaluationFlag> flags,
                @Nullable TestEvidence tests, boolean testsExpected) {
            this(expectedKeyPoints, keyPoints, factualErrors, flags, tests, testsExpected, Map.of(), null);
        }

        /** The evidence of a system design answer: its errors by dimension and its verified quotes by dimension. */
        public static Evidence design(int expectedKeyPoints, List<KeyPointAssessment> keyPoints, int factualErrors, Set<EvaluationFlag> flags,
                Map<Criterion, Integer> errorsByCriterion, Map<Criterion, Integer> quotesByCriterion) {
            return new Evidence(expectedKeyPoints, keyPoints, factualErrors, flags, null, false, errorsByCriterion,
                    Require.nonNull(quotesByCriterion, "quotesByCriterion"));
        }

        /** The evidence of an answer that is not a coding solution. */
        public Evidence(int expectedKeyPoints, List<KeyPointAssessment> keyPoints, int factualErrors, Set<EvaluationFlag> flags) {
            this(expectedKeyPoints, keyPoints, factualErrors, flags, null, false);
        }

        /** {@code ceil(4 * coverage)}: the completeness the verified key points support; 4 when the question expects none. */
        public int coverageCap() {
            if (expectedKeyPoints == 0) {
                return CriterionScore.MAX_RATING;
            }
            int halfPoints = 0;
            for (KeyPointAssessment keyPoint : keyPoints) {
                halfPoints += keyPoint.status() == KeyPointStatus.COVERED ? 2 : keyPoint.status() == KeyPointStatus.PARTIAL ? 1 : 0;
            }
            // 4 * (halfPoints / 2) / expected, rounded up, in integers.
            return Math.min(CriterionScore.MAX_RATING, Math.ceilDiv(2 * halfPoints, expectedKeyPoints));
        }
    }

    /**
     * A rating after the rules: lowered from {@code modelRating} by {@code rule}, decided by a measurement ({@code rule} is
     * {@link Rule#TEST_RESULTS}, {@code modelRating} what the model said), or as the model gave it (both null).
     */
    public record Outcome(int rating, @Nullable Integer modelRating, @Nullable Rule rule) {

        public boolean capped() {
            return rule != null && !rule.measurement();
        }

        public boolean measured() {
            return rule != null && rule.measurement();
        }
    }

    private RatingRules() {
    }

    /**
     * @param ratings the model's rating per criterion, 0 to 4
     * @return the rating to score per criterion, in the same criteria
     */
    public static Map<Criterion, Outcome> apply(Map<Criterion, Integer> ratings, Evidence evidence) {
        Require.nonNull(ratings, "ratings");
        Require.nonNull(evidence, "evidence");
        Map<Criterion, Outcome> outcomes = new EnumMap<>(Criterion.class);
        ratings.forEach((criterion, rating) -> {
            if (criterion == Criterion.CORRECTNESS && evidence.tests() != null) {
                outcomes.put(criterion, new Outcome(evidence.tests().correctnessRating(), rating, Rule.TEST_RESULTS));
                return;
            }
            int cap = rating;
            Rule rule = null;
            for (Rule candidate : Rule.values()) {
                Integer limit = limit(candidate, criterion, evidence);
                if (limit != null && limit < cap) {
                    cap = limit;
                    rule = candidate;
                }
            }
            outcomes.put(criterion, rule == null ? new Outcome(rating, null, null) : new Outcome(cap, rating, rule));
        });
        return outcomes;
    }

    /** Correctness is capped by every error of the answer; a design dimension by the errors that concern it. */
    private static @Nullable Integer factualErrorLimit(Criterion criterion, Evidence evidence) {
        int errors = criterion == Criterion.CORRECTNESS ? evidence.factualErrors() : evidence.errorsByCriterion().getOrDefault(criterion, 0);
        return errors > 0 ? Math.max(1, CriterionScore.MAX_RATING - errors) : null;
    }

        /**
     * What a flagged answer is bounded by: its verified key points; for a coding solution, what its tests proved (at least 1);
     * for a coding solution whose tests did not run, 2 ("adequate"), since nothing measured backs the model's rating. The
     * introduction has no evidence and does not count toward the score, so it is not bounded.
     */
    private static @Nullable Integer injectionLimit(Evidence evidence) {
        if (evidence.expectedKeyPoints() > 0) {
            return Math.max(1, evidence.coverageCap());
        }
        if (evidence.tests() != null) {
            return Math.max(1, evidence.tests().correctnessRating());
        }
        return evidence.testsExpected() ? 2 : null;
    }

    /** The highest rating {@code rule} allows for {@code criterion}, or null when the rule does not apply to it. */
    private static @Nullable Integer limit(Rule rule, Criterion criterion, Evidence evidence) {
        return switch (rule) {
            case FACTUAL_ERRORS -> factualErrorLimit(criterion, evidence);
            case KEY_POINT_COVERAGE -> criterion == Criterion.COMPLETENESS && evidence.expectedKeyPoints() > 0
                    ? evidence.coverageCap() : null;
            case OFF_TOPIC -> evidence.flags().contains(EvaluationFlag.OFF_TOPIC) ? 1 : null;
            case INJECTION_EVIDENCE -> evidence.flags().contains(EvaluationFlag.INJECTION_SUSPECTED) ? injectionLimit(evidence) : null;
            case DESIGN_EVIDENCE -> evidence.quotesByCriterion() != null && evidence.quotesByCriterion().getOrDefault(criterion, 0) == 0
                    ? 1 : null;
            case EDGE_CASE_TESTS -> criterion == Criterion.EDGE_CASES && evidence.tests() != null ? evidence.tests().edgeCaseCap() : null;
            case PERFORMANCE_TESTS -> criterion == Criterion.TIME_COMPLEXITY && evidence.tests() != null && evidence.tests().performanceTimedOut()
                    ? 1 : null;
            case NO_TEST_EVIDENCE -> criterion == Criterion.CORRECTNESS && evidence.testsExpected() && evidence.tests() == null ? 3 : null;
            case TEST_RESULTS -> null;
        };
    }
}
