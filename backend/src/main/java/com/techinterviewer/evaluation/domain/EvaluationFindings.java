package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the evaluation of one answer found. The ratings carry the server's weights; the overall score derived from them
 * is stored next to these findings by {@link Evaluation}.
 *
 * @param criterionScores one entry per rated criterion, at least one, no criterion twice
 * @param keyPoints       how each expected key point fared
 * @param factualErrors   wrong statements found in the answer
 * @param candidateClaims claims the candidate made about their own experience
 * @param summary         a short justification
 * @param flags           integrity and quality signals
 * @param confidence      how sure the evaluator is, after the server's adjustments; null for an evaluator that does not state it
 *                        (the introduction) and for evaluations written before it was asked for
 * @param codeAnalysis    the approach and complexity of a coding solution; null for other answers
 */
public record EvaluationFindings(
        List<CriterionScore> criterionScores,
        List<KeyPointAssessment> keyPoints,
        List<FactualError> factualErrors,
        List<String> candidateClaims,
        String summary,
        Set<EvaluationFlag> flags,
        @Nullable EvaluatorConfidence confidence,
        @Nullable CodeAnalysis codeAnalysis) {

    public static final int MAX_SUMMARY_LENGTH = 4000;
    public static final int MAX_CLAIM_LENGTH = 300;

    public EvaluationFindings {
        criterionScores = Immutables.list(criterionScores);
        keyPoints = Immutables.list(keyPoints);
        factualErrors = Immutables.list(factualErrors);
        candidateClaims = Immutables.list(candidateClaims);
        flags = Immutables.enumSet(flags, EvaluationFlag.class);
        if (criterionScores.isEmpty()) {
            throw new IllegalArgumentException("criterionScores must not be empty");
        }
        Set<Criterion> seen = EnumSet.noneOf(Criterion.class);
        for (CriterionScore score : criterionScores) {
            if (!seen.add(score.criterion())) {
                throw new IllegalArgumentException("criterion " + score.criterion() + " is rated twice");
            }
        }
        Set<String> keyPointIds = new HashSet<>();
        for (KeyPointAssessment assessment : keyPoints) {
            if (!keyPointIds.add(assessment.keyPointId())) {
                throw new IllegalArgumentException("key point " + assessment.keyPointId() + " is assessed twice");
            }
        }
        candidateClaims.forEach(claim -> Require.notBlank(claim, "candidate claim", MAX_CLAIM_LENGTH));
        Require.notBlank(summary, "summary", MAX_SUMMARY_LENGTH);
    }

    /** Findings without a stated confidence. */
    public EvaluationFindings(
            List<CriterionScore> criterionScores,
            List<KeyPointAssessment> keyPoints,
            List<FactualError> factualErrors,
            List<String> candidateClaims,
            String summary,
            Set<EvaluationFlag> flags) {
        this(criterionScores, keyPoints, factualErrors, candidateClaims, summary, flags, null, null);
    }

    /** Findings of an answer that is not a coding solution. */
    public EvaluationFindings(
            List<CriterionScore> criterionScores,
            List<KeyPointAssessment> keyPoints,
            List<FactualError> factualErrors,
            List<String> candidateClaims,
            String summary,
            Set<EvaluationFlag> flags,
            @Nullable EvaluatorConfidence confidence) {
        this(criterionScores, keyPoints, factualErrors, candidateClaims, summary, flags, confidence, null);
    }
}
