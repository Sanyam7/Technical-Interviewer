package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link QuestionGrounding}: a chunk grounds a question at most once. */
@Embeddable
public record QuestionGroundingId(
        @Column(name = "question_id", nullable = false, updatable = false) UUID questionId,
        @Column(name = "chunk_id", nullable = false, updatable = false) UUID chunkId) implements Serializable {

    public QuestionGroundingId {
        Require.nonNull(questionId, "questionId");
        Require.nonNull(chunkId, "chunkId");
    }
}
