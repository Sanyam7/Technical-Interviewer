package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;

/**
 * How sure the evaluator says it is of one evaluation, and why. This is the model's own estimate: it is <b>not
 * statistically calibrated</b> (a HIGH is not a probability, and nothing guarantees that HIGH evaluations are right more
 * often than LOW ones). It never changes a score. The server only lowers it when its own checks contradict the model
 * ({@code ConfidencePolicy}), and an evaluation that ends LOW is marked for human review.
 *
 * @param level  LOW, MEDIUM or HIGH
 * @param reason one short sentence, at most {@value #MAX_REASON_LENGTH} characters
 */
public record EvaluatorConfidence(Confidence level, String reason) {

    public static final int MAX_REASON_LENGTH = 500;

    public EvaluatorConfidence {
        Require.nonNull(level, "level");
        Require.notBlank(reason, "reason", MAX_REASON_LENGTH);
    }
}
