package com.techinterviewer.interview.domain;

/**
 * Why an evaluation attempt failed, none of it the candidate's doing. The constants equal the
 * {@code ck_answers_evaluation_failure} check.
 */
public enum EvaluationFailure {
    /** The provider was unavailable, timed out, was rate limited or failed unexpectedly. Transient: retried. */
    PROVIDER_FAILURE,
    /** The evaluator returned output that failed validation against the question. Retried. */
    INVALID_OUTPUT,
    /** The provider refused to judge the answer (content safety). Not retried: the same content gets the same refusal. */
    SAFETY_BLOCK;

    /** Whether another attempt can help. */
    public boolean retryable() {
        return this != SAFETY_BLOCK;
    }
}
