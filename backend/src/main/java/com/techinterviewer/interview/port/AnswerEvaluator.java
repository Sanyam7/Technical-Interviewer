package com.techinterviewer.interview.port;

/**
 * Judges one answer. Implemented by the AI layer (prompt, structured output, evidence verification); the engine
 * turns the ratings into a score. Blocking, and <b>never called inside a database transaction</b>.
 */
public interface AnswerEvaluator {

    /**
     * @throws AiUnavailableException when no verified assessment can be produced (provider down, timed out, rate limited,
     *                                output invalid after the adapter's own retries). The backend counts it as a failed
     *                                attempt: the answer stays {@code PENDING} and is tried again, at most
     *                                {@code app.evaluation.max-attempts} times in all, then it is {@code UNASSESSABLE}
     * @throws AnswerBlockedException when the provider refuses to judge the answer for safety reasons; not retried, the
     *                                answer becomes {@code UNASSESSABLE} at once
     * @throws IllegalArgumentException when the adapter built an unusable result (see the port records); counted as an
     *                                  invalid output
     */
    AnswerAssessment evaluate(EvaluationRequest request);
}
