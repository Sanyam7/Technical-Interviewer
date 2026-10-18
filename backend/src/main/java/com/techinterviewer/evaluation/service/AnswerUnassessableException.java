package com.techinterviewer.evaluation.service;

import java.util.UUID;

/**
 * An evaluation arrived for an answer that was given up (UNASSESSABLE) meanwhile. The state is final, so the late
 * evaluation is discarded; the caller treats the answer as unassessable.
 */
public class AnswerUnassessableException extends RuntimeException {

    public AnswerUnassessableException(UUID answerId) {
        super("answer " + answerId + " was given up and is unassessable; a late evaluation is discarded");
    }
}
