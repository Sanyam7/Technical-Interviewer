package com.techinterviewer.evaluation.domain;

/**
 * Integrity and quality signals attached to an evaluation. Stored as text in {@code evaluations.flags}
 * (a {@code text[]} without check), so a constant can be added without a migration but must never be renamed
 * or removed while rows reference it.
 */
public enum EvaluationFlag {
    /** The answer looks like an attempt to instruct the evaluator (heuristic; never zeroes the score). */
    INJECTION_SUSPECTED,
    /** The evaluator quoted evidence that is not in the answer; the key point was downgraded. */
    EVAL_EVIDENCE_MISMATCH,
    /** The answer does not address the question. */
    OFF_TOPIC,
    /** A code answer is not written in the language the interview was configured for. */
    LANGUAGE_MISMATCH,
    /**
     * The evaluation ended with LOW evaluator confidence (the model's own estimate, possibly lowered by the server's
     * checks): the answer is worth a human review. Set by the server only; never changes the score.
     */
    LOW_EVALUATOR_CONFIDENCE
}
