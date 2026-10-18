package com.techinterviewer.evaluation.service;

/**
 * The evaluator's assessment does not fit the question it was made for: it rates other criteria than the rubric of
 * the question, cites a key point the question does not have, or exceeds a bound. Such an output is never stored and
 * never becomes a score; the answer stays as it was (PENDING) and is evaluated again later.
 *
 * <p>The message names the rule that was broken, never the answer text.
 */
public class InvalidAssessmentException extends RuntimeException {

    public InvalidAssessmentException(String message) {
        super(message);
    }

    public InvalidAssessmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
