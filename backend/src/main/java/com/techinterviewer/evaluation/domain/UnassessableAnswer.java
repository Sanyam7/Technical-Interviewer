package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.Section;
import java.util.UUID;

/**
 * An answer that could not be assessed, as a report discloses it. It says nothing about the quality of the answer, which is
 * left out of every score instead of being scored zero.
 *
 * @param questionId the question that was answered
 * @param sequenceNo position of the question in the interview, from 1
 * @param section    the section of the question
 * @param reason     why no assessment was possible
 */
public record UnassessableAnswer(UUID questionId, int sequenceNo, Section section, EvaluationFailure reason) {

    public UnassessableAnswer {
        Require.nonNull(questionId, "questionId");
        Require.atLeast(sequenceNo, 1, "sequenceNo");
        Require.nonNull(section, "section");
        Require.nonNull(reason, "reason");
    }
}
