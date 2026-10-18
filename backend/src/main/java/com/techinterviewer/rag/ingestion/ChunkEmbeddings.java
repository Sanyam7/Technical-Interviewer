package com.techinterviewer.rag.ingestion;

import com.techinterviewer.rag.RagProperties;
import com.techinterviewer.rag.corpus.CorpusParser;
import com.techinterviewer.rag.retrieval.ChunkSearch;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code embeddings} table, in SQL because Hibernate does not map {@code vector}. A chunk has at most one vector per
 * model; the vector is computed from the chunk's heading path and text, whose hash is stored with it.
 */
public final class ChunkEmbeddings {

    /** A chunk of a READY document that has no vector of the active model yet. */
    public record Pending(UUID chunkId, String embeddingText) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ChunkEmbeddings(JdbcClient jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Up to {@code limit} chunks without a vector of {@code model}, in document order. */
    public List<Pending> missing(String model, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.heading_path, c.content
                        FROM knowledge_chunks c
                        JOIN knowledge_documents d ON d.id = c.document_id
                        WHERE d.status = 'READY'
                          AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.chunk_id = c.id AND e.model = :model)
                        ORDER BY c.document_id, c.chunk_index
                        LIMIT :limit""")
                .param("model", model)
                .param("limit", limit)
                .query((rows, n) -> new Pending(rows.getObject("id", UUID.class),
                        embeddingText(rows.getString("heading_path"), rows.getString("content"))))
                .list();
    }

    public long countMissing(String model) {
        return jdbc.sql("""
                        SELECT count(*) FROM knowledge_chunks c
                        JOIN knowledge_documents d ON d.id = c.document_id
                        WHERE d.status = 'READY'
                          AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.chunk_id = c.id AND e.model = :model)""")
                .param("model", model).query(Long.class).single();
    }

    /** Stores one vector per chunk; a chunk that got its vector meanwhile, or was deleted meanwhile, is skipped. */
    public void save(String model, List<Pending> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException(chunks.size() + " chunks but " + vectors.size() + " vectors");
        }
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < chunks.size(); i++) {
                jdbc.sql("""
                                INSERT INTO embeddings (id, chunk_id, model, dimensions, embedding, content_sha256, created_at)
                                SELECT :id, c.id, :model, :dimensions, CAST(:embedding AS vector), :sha, :now
                                FROM knowledge_chunks c WHERE c.id = :chunkId
                                ON CONFLICT (chunk_id, model) DO NOTHING""")
                        .param("id", UUID.randomUUID())
                        .param("chunkId", chunks.get(i).chunkId())
                        .param("model", model)
                        .param("dimensions", RagProperties.DIMENSIONS)
                        .param("embedding", ChunkSearch.literal(vectors.get(i)))
                        .param("sha", CorpusParser.sha256(chunks.get(i).embeddingText().getBytes(StandardCharsets.UTF_8)))
                        .param("now", java.sql.Timestamp.from(clock.instant()))
                        .update();
            }
        });
    }

    static String embeddingText(String headingPath, String content) {
        return headingPath == null ? content : headingPath + "\n\n" + content;
    }
}
