package com.techinterviewer.rag.dedup;

import com.techinterviewer.interview.port.QuestionEmbedding;
import com.techinterviewer.rag.embedding.EmbeddingService;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * De-duplication by embedding: the question is embedded for similarity (one call, the query timeout, no retry) and
 * compared with the vectors stored in {@code questions.embedding} for the same interview and model (DATABASE_SCHEMA.md
 * section 4.2). At about 30 questions per interview a sequential scan is the right plan; no index is needed.
 *
 * <p>Never fails the generation: an embedding that cannot be made leaves the question to the lexical check.
 */
public final class EmbeddingQuestionDeduplicator implements QuestionDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingQuestionDeduplicator.class);

    private final EmbeddingService embeddings;
    private final JdbcClient jdbc;
    private final double minSimilarity;

    public EmbeddingQuestionDeduplicator(EmbeddingService embeddings, JdbcClient jdbc, double minSimilarity) {
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.minSimilarity = minSimilarity;
    }

    @Override
    public @Nullable QuestionEmbedding embed(UUID interviewId, String question) {
        try {
            return new QuestionEmbedding(embeddings.model(), embeddings.embedForSimilarity(question, interviewId));
        } catch (RuntimeException failure) {
            log.warn("A question of interview {} could not be embedded; only the lexical duplicate check applies: {}", interviewId,
                    failure.getMessage());
            return null;
        }
    }

    @Override
    public Optional<Repeat> repeatOf(UUID interviewId, QuestionEmbedding embedding) {
        return jdbc.sql("""
                        SELECT id, 1 - (embedding <=> CAST(:vector AS vector)) AS similarity
                        FROM questions
                        WHERE interview_id = :interview AND embedding_model = :model
                        ORDER BY embedding <=> CAST(:vector AS vector)
                        LIMIT 1""")
                .param("vector", embedding.literal())
                .param("interview", interviewId)
                .param("model", embedding.model())
                .query((row, number) -> new Repeat(row.getObject("id", UUID.class), row.getDouble("similarity")))
                .optional()
                .filter(nearest -> nearest.similarity() >= minSimilarity);
    }

    /** The configured threshold, for the calibration tests. */
    public double minSimilarity() {
        return minSimilarity;
    }
}
