package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;
import java.util.UUID;

/**
 * A remark in a report about the reliability of one evaluation, derived from an {@link EvaluationFlag}
 * ({@code reports.integrity_notes}).
 *
 * @param flag         the signal
 * @param evaluationId the evaluation that carried it
 * @param note         what the reader should know
 */
public record IntegrityNote(EvaluationFlag flag, UUID evaluationId, String note) {

    public static final int MAX_NOTE_LENGTH = 500;

    public IntegrityNote {
        Require.nonNull(flag, "flag");
        Require.nonNull(evaluationId, "evaluationId");
        Require.notBlank(note, "note", MAX_NOTE_LENGTH);
    }
}
