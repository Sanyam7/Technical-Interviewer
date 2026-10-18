package com.techinterviewer.interview.port;

/**
 * The provider refused to evaluate this answer for content-safety reasons. Unlike {@link AiUnavailableException} it is
 * not transient: the answer is marked {@code UNASSESSABLE} at once instead of being retried. It says nothing about the
 * quality of the answer, which is never scored zero because of it.
 */
public class AnswerBlockedException extends RuntimeException {

    public AnswerBlockedException(String message) {
        super(message);
    }

    public AnswerBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
