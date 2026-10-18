package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the server does with the evaluator's stated confidence (AI_ARCHITECTURE.md section 2.10). The model's estimate
 * is kept as it is unless the server's own checks give a reason to doubt the evaluation, and then it is only lowered:
 *
 * <ul>
 *   <li>the evaluator quoted words the answer does not contain ({@code EVAL_EVIDENCE_MISMATCH}): LOW;
 *   <li>the answer tried to instruct the evaluator ({@code INJECTION_SUSPECTED}): at most MEDIUM;
 *   <li>a rating contradicted the evaluator's own findings and was lowered ({@link RatingRules}): at most MEDIUM.
 * </ul>
 *
 * The confidence is a self-report, not a calibrated probability: it never changes a score. It only routes attention,
 * since an evaluation that ends LOW is flagged {@code LOW_EVALUATOR_CONFIDENCE} and listed for human review.
 */
public final class ConfidencePolicy {

    private ConfidencePolicy() {
    }

    /**
     * @param stated       the model's estimate; null when the evaluator does not state one (the introduction)
     * @param flags        the evaluation's flags
     * @param ratingCapped true when a rating rule lowered one of the model's ratings
     * @param testsDisagree true when the tests set a correctness at least two points away from the model's
     * @return the confidence to store, null when none was stated
     */
    /** {@link #adjust(EvaluatorConfidence, Set, boolean, boolean)} for an answer without tests. */
    public static @Nullable EvaluatorConfidence adjust(@Nullable EvaluatorConfidence stated, Set<EvaluationFlag> flags, boolean ratingCapped) {
        return adjust(stated, flags, ratingCapped, false);
    }

    public static @Nullable EvaluatorConfidence adjust(@Nullable EvaluatorConfidence stated, Set<EvaluationFlag> flags, boolean ratingCapped,
            boolean testsDisagree) {
        if (stated == null) {
            return null;
        }
        Confidence level = stated.level();
        List<String> doubts = new ArrayList<>();
        if (flags.contains(EvaluationFlag.EVAL_EVIDENCE_MISMATCH)) {
            level = Confidence.LOW;
            doubts.add("a quoted piece of evidence is not in the answer");
        }
        if (flags.contains(EvaluationFlag.INJECTION_SUSPECTED)) {
            level = level.atMost(Confidence.MEDIUM);
            doubts.add("the answer tried to instruct the evaluator");
        }
        if (ratingCapped) {
            level = level.atMost(Confidence.MEDIUM);
            doubts.add("a rating contradicted the evaluation's own findings");
        }
        if (testsDisagree) {
            level = level.atMost(Confidence.MEDIUM);
            doubts.add("the tests disagreed with the evaluator's judgement of correctness");
        }
        if (level == stated.level()) {
            return stated;
        }
        String reason = stated.reason() + " [Lowered from " + stated.level() + ": " + String.join("; ", doubts) + ".]";
        int max = EvaluatorConfidence.MAX_REASON_LENGTH;
        return new EvaluatorConfidence(level, reason.length() <= max ? reason : reason.substring(0, max - 3) + "...");
    }
}
