package com.techinterviewer.evaluation.service;

import java.util.UUID;

/**
 * An answer has a stored evaluation already and the assessment offered now differs from it. An evaluation is written
 * once and never changed, so the new one is refused; offering the very same assessment again is not an error.
 */
public class AnswerAlreadyEvaluatedException extends RuntimeException {

    private final UUID answerId;

    public AnswerAlreadyEvaluatedException(UUID answerId) {
        super("answer " + answerId + " has been evaluated already with a different assessment");
        this.answerId = answerId;
    }

    public UUID answerId() {
        return answerId;
    }
}
