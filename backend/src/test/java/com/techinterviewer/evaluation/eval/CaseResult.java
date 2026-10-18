package com.techinterviewer.evaluation.eval;

import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What one run of the evaluator made of one case, after the server's verification, rules and weights: exactly what a
 * candidate would have been given.
 *
 * @param testCase        the case
 * @param run             1 for the first run of the case, 2 for the repeat, ...
 * @param score           the computed score, 0 to 100; null when the evaluation failed
 * @param modelRatings    the model's own ratings
 * @param scoredRatings   the ratings the score was computed from (lowered by a rule where the model contradicted itself)
 * @param keyPoints       the verified verdict per key point id
 * @param factualErrors   the claims the evaluation listed as wrong
 * @param flags           the evaluation's flags
 * @param modelConfidence the confidence the model stated
 * @param confidence      the confidence after the server's adjustment
 * @param capped          one line per lowered rating, such as {@code CORRECTNESS 4->2 FACTUAL_ERRORS}
 * @param callStatus      how the structured call ended: OK (valid first time), REPAIRED, or a failure
 * @param millis          wall-clock time of the evaluation
 * @param failure         why the evaluation failed, null when it did not
 */
public record CaseResult(
        AnswerEvaluationCase testCase,
        int run,
        @Nullable Double score,
        Map<Criterion, Integer> modelRatings,
        Map<Criterion, Integer> scoredRatings,
        Map<String, KeyPointStatus> keyPoints,
        List<String> factualErrors,
        Set<EvaluationFlag> flags,
        @Nullable Confidence modelConfidence,
        @Nullable Confidence confidence,
        List<String> capped,
        @Nullable LlmCallStatus callStatus,
        long millis,
        @Nullable String failure) {

    public boolean evaluated() {
        return score != null;
    }

    public static CaseResult failed(AnswerEvaluationCase testCase, int run, @Nullable LlmCallStatus status, long millis, String failure) {
        return new CaseResult(testCase, run, null, Map.of(), Map.of(), Map.of(), List.of(), Set.of(), null, null, List.of(), status, millis, failure);
    }
}
