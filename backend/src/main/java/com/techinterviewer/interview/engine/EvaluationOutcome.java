package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What became of an answer, as far as the engine is concerned: it was evaluated and scored, its evaluation is still
 * pending (the evaluator was unavailable), or the candidate skipped the question.
 *
 * @param status    SCORED, PENDING or SKIPPED
 * @param score     overall score 0 to 100, computed by the {@code ScoreAggregator}; only for SCORED
 * @param summary   the evaluator's one-line summary; only for SCORED
 * @param keyPoints how each expected key point fared; only for SCORED
 * @param newClaims claims the candidate made about their own experience; only for SCORED
 * @param flags     integrity and quality flags; only for SCORED
 * @param misconceptions the claims of the evaluation's factual errors; only for SCORED
 * @param criteria  the scored rating and weight of each rubric criterion, in rubric order; only for SCORED (a design thread
 *                  chooses its next probe from them)
 * @param reliable  false when the evaluation is too doubtful to move a difficulty level: its confidence ended LOW (the server
 *                  could not verify its evidence, or the evaluator itself was unsure). Its score still counts everywhere else
 *                  (AI_ARCHITECTURE.md section 2.15). Always true unless SCORED
 */
public record EvaluationOutcome(
        Status status,
        @Nullable Double score,
        @Nullable String summary,
        List<KeyPointAssessment> keyPoints,
        List<String> newClaims,
        Set<EvaluationFlag> flags,
        List<String> misconceptions,
        List<CriterionScore> criteria,
        boolean reliable) {

    public enum Status {
        SCORED,
        PENDING,
        SKIPPED
    }

    public EvaluationOutcome {
        Require.nonNull(status, "status");
        keyPoints = Immutables.list(keyPoints);
        newClaims = Immutables.list(newClaims);
        flags = Immutables.enumSet(flags, EvaluationFlag.class);
        misconceptions = Immutables.list(misconceptions);
        criteria = Immutables.list(criteria);
        if (status == Status.SCORED) {
            Require.between(Require.nonNull(score, "score"), 0, 100, "score");
            Require.notBlank(summary, "summary", AnswerSummary.MAX_LENGTH);
            newClaims.forEach(claim -> Require.notBlank(claim, "newClaims entry", Claim.MAX_LENGTH));
            misconceptions.forEach(claim -> Require.notBlank(claim, "misconceptions entry"));
        } else if (score != null || summary != null || !keyPoints.isEmpty() || !newClaims.isEmpty() || !flags.isEmpty()
                || !misconceptions.isEmpty() || !criteria.isEmpty() || !reliable) {
            throw new IllegalArgumentException(
                    "only a scored outcome carries a score, summary, key points, claims, flags, misconceptions, criteria or doubt");
        }
    }

    /** An outcome whose evaluation is reliable. */
    public EvaluationOutcome(Status status, @Nullable Double score, @Nullable String summary, List<KeyPointAssessment> keyPoints,
            List<String> newClaims, Set<EvaluationFlag> flags, List<String> misconceptions, List<CriterionScore> criteria) {
        this(status, score, summary, keyPoints, newClaims, flags, misconceptions, criteria, true);
    }

    /** The same scored outcome, from an evaluation too doubtful to move a difficulty level. */
    public EvaluationOutcome unreliable() {
        if (status != Status.SCORED) {
            throw new IllegalStateException("only a scored outcome can be doubted");
        }
        return new EvaluationOutcome(status, score, summary, keyPoints, newClaims, flags, misconceptions, criteria, false);
    }

    /** An outcome without criterion scores. */
    public EvaluationOutcome(Status status, @Nullable Double score, @Nullable String summary, List<KeyPointAssessment> keyPoints,
            List<String> newClaims, Set<EvaluationFlag> flags, List<String> misconceptions) {
        this(status, score, summary, keyPoints, newClaims, flags, misconceptions, List.of());
    }

    public static EvaluationOutcome scored(
            double score, String summary, List<KeyPointAssessment> keyPoints, List<String> newClaims, Set<EvaluationFlag> flags) {
        return scored(score, summary, keyPoints, newClaims, flags, List.of());
    }

    public static EvaluationOutcome scored(double score, String summary, List<KeyPointAssessment> keyPoints, List<String> newClaims,
            Set<EvaluationFlag> flags, List<String> misconceptions) {
        return new EvaluationOutcome(Status.SCORED, score, summary, keyPoints, newClaims, flags, misconceptions);
    }

    public static EvaluationOutcome scored(double score, String summary, List<KeyPointAssessment> keyPoints, List<String> newClaims,
            Set<EvaluationFlag> flags, List<String> misconceptions, List<CriterionScore> criteria) {
        return new EvaluationOutcome(Status.SCORED, score, summary, keyPoints, newClaims, flags, misconceptions, criteria);
    }

    public static EvaluationOutcome pending() {
        return new EvaluationOutcome(Status.PENDING, null, null, List.of(), List.of(), Set.of(), List.of());
    }

    public static EvaluationOutcome skipped() {
        return new EvaluationOutcome(Status.SKIPPED, null, null, List.of(), List.of(), Set.of(), List.of());
    }
}
