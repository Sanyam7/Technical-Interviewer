package com.techinterviewer.interview.domain;

import com.techinterviewer.common.persistence.AssignedIdEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A knowledge chunk that grounded a generated question, with its place in the retrieval ranking (table
 * {@code question_sources}). Rows are written from retrieval results, once, and removed by the database together
 * with the question or the chunk.
 */
@Entity
@Table(name = "question_sources")
public class QuestionGrounding extends AssignedIdEntity<QuestionGroundingId> {

    @EmbeddedId
    private QuestionGroundingId id;

    @Column(name = "rank", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int rank;

    @Column(name = "fused_score", nullable = false, updatable = false)
    private float fusedScore;

    protected QuestionGrounding() {
    }

    /**
     * @param rank       position in the retrieval result, starting at 1
     * @param fusedScore the fused retrieval score (not NaN or infinite)
     */
    public QuestionGrounding(UUID questionId, UUID chunkId, int rank, float fusedScore) {
        this.id = new QuestionGroundingId(questionId, chunkId);
        this.rank = Require.atLeast(rank, 1, "rank");
        if (!Float.isFinite(fusedScore)) {
            throw new IllegalArgumentException("fusedScore must be a finite number but was " + fusedScore);
        }
        this.fusedScore = fusedScore;
    }

    @Override
    public QuestionGroundingId getId() {
        return id;
    }

    public UUID getQuestionId() {
        return id.questionId();
    }

    public UUID getChunkId() {
        return id.chunkId();
    }

    public int getRank() {
        return rank;
    }

    public float getFusedScore() {
        return fusedScore;
    }
}
