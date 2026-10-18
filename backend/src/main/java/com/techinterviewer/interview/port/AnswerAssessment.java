package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.CodeAnalysis;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The evaluator's verified judgement of one answer. It deliberately carries <b>no score</b>: the overall score is
 * computed by the server from the criterion ratings and the rubric weights, never taken from a model.
 *
 * @param ratings         rating and rationale per criterion; at least one
 * @param keyPoints       one entry per expected key point the evaluator judged
 * @param factualErrors   wrong statements found in the answer
 * @param candidateClaims statements about the candidate's own experience, for follow-up probing
 * @param summary         one sentence for the interview memory
 * @param flags           integrity and quality flags
 * @param promptId        prompt template used ({@code evaluations.prompt_id})
 * @param promptVersion   its version
 * @param model           model that answered
 * @param llmCallId       id of the recorded model call, stored as {@code evaluations.llm_call_id}
 * @param confidence      how sure the evaluator says it is; null when it does not state it (the introduction). Not calibrated
 * @param codeAnalysis    the approach and complexity of a coding solution; null for other answers
 */
public record AnswerAssessment(
        Map<Criterion, CriterionRating> ratings,
        List<KeyPointAssessment> keyPoints,
        List<FactualError> factualErrors,
        List<String> candidateClaims,
        String summary,
        Set<EvaluationFlag> flags,
        String promptId,
        String promptVersion,
        String model,
        @Nullable UUID llmCallId,
        @Nullable EvaluatorConfidence confidence,
        @Nullable CodeAnalysis codeAnalysis) {

    public AnswerAssessment {
        ratings = Immutables.enumMap(ratings, Criterion.class);
        if (ratings.isEmpty()) {
            throw new IllegalArgumentException("ratings must not be empty");
        }
        keyPoints = Immutables.list(keyPoints);
        Set<String> ids = new HashSet<>();
        for (KeyPointAssessment keyPoint : keyPoints) {
            if (!ids.add(keyPoint.keyPointId())) {
                throw new IllegalArgumentException("duplicate key point assessment " + keyPoint.keyPointId());
            }
        }
        factualErrors = Immutables.list(factualErrors);
        candidateClaims = Immutables.list(candidateClaims);
        candidateClaims.forEach(claim -> Require.notBlank(claim, "candidateClaims entry", Claim.MAX_LENGTH));
        Require.notBlank(summary, "summary", AnswerSummary.MAX_LENGTH);
        flags = Immutables.enumSet(flags, EvaluationFlag.class);
        Require.notBlank(promptId, "promptId", 60);
        Require.notBlank(promptVersion, "promptVersion", 20);
        Require.notBlank(model, "model", 80);
    }

    /** An assessment without a stated confidence. */
    public AnswerAssessment(
            Map<Criterion, CriterionRating> ratings,
            List<KeyPointAssessment> keyPoints,
            List<FactualError> factualErrors,
            List<String> candidateClaims,
            String summary,
            Set<EvaluationFlag> flags,
            String promptId,
            String promptVersion,
            String model,
            @Nullable UUID llmCallId) {
        this(ratings, keyPoints, factualErrors, candidateClaims, summary, flags, promptId, promptVersion, model, llmCallId, null, null);
    }

    /** An assessment of an answer that is not a coding solution. */
    public AnswerAssessment(
            Map<Criterion, CriterionRating> ratings,
            List<KeyPointAssessment> keyPoints,
            List<FactualError> factualErrors,
            List<String> candidateClaims,
            String summary,
            Set<EvaluationFlag> flags,
            String promptId,
            String promptVersion,
            String model,
            @Nullable UUID llmCallId,
            @Nullable EvaluatorConfidence confidence) {
        this(ratings, keyPoints, factualErrors, candidateClaims, summary, flags, promptId, promptVersion, model, llmCallId, confidence, null);
    }
}
