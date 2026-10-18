package com.techinterviewer.rag.retrieval;

import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Technology;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two halves of the hybrid search in SQL, and the lookup of the chunks they found. Every query applies the same
 * filters: the document is READY, PUBLIC or owned by the asking user, of an allowed technology and type, and the
 * chunk's difficulty (when tagged) is within one level of the requested one.
 *
 * <ul>
 *   <li>Vector half: cosine distance ({@code <=>}) on the HNSW index, only over vectors of the active model. HNSW with
 *       a selective filter can return fewer rows than asked for; {@code hnsw.iterative_scan} makes the index keep
 *       scanning until the limit is met, and a larger {@code ef_search} improves recall.
 *   <li>Keyword half: the OR of one {@code plainto_tsquery} per term against the generated {@code content_tsv}
 *       (heading path included), ranked by {@code ts_rank_cd}. The terms are OR-ed: an AND of every term of a long
 *       query matches almost nothing.
 * </ul>
 */
public final class ChunkSearch {

    /** A chunk found by the vector half. */
    public record VectorHit(UUID chunkId, double similarity) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    public ChunkSearch(JdbcClient jdbc, TransactionTemplate readOnly) {
        this.jdbc = jdbc;
        this.readOnly = readOnly;
    }

    public List<VectorHit> vector(float[] query, String model, RetrievalQuery filter, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", literal(query));
        params.put("model", model);
        params.put("limit", limit);
        String sql = """
                SELECT c.id, 1 - (e.embedding <=> CAST(:query AS vector)) AS similarity
                FROM embeddings e
                JOIN knowledge_chunks c ON c.id = e.chunk_id
                JOIN knowledge_documents d ON d.id = c.document_id
                WHERE e.model = :model AND %s
                ORDER BY e.embedding <=> CAST(:query AS vector), c.id
                LIMIT :limit""".formatted(filters(filter, params));
        return readOnly.execute(status -> {
            jdbc.sql("SET LOCAL hnsw.ef_search = 100").update();
            jdbc.sql("SET LOCAL hnsw.iterative_scan = strict_order").update();
            return jdbc.sql(sql).params(params)
                    .query((rows, n) -> new VectorHit(rows.getObject("id", UUID.class), rows.getDouble("similarity")))
                    .list();
        });
    }

    public List<UUID> keyword(List<String> terms, RetrievalQuery filter, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            params.put("term" + i, terms.get(i));
            parts.add("plainto_tsquery('english', :term" + i + ")");
        }
        params.put("limit", limit);
        String sql = """
                WITH q AS (SELECT %s AS query)
                SELECT c.id
                FROM q, knowledge_chunks c
                JOIN knowledge_documents d ON d.id = c.document_id
                WHERE numnode(q.query) > 0 AND c.content_tsv @@ q.query AND %s
                ORDER BY ts_rank_cd(c.content_tsv, q.query) DESC, c.id
                LIMIT :limit""".formatted(String.join(" || ", parts), filters(filter, params));
        return jdbc.sql(sql).params(params).query(UUID.class).list();
    }

    /** The chunks with these ids that pass the visibility rule of {@code userId} (null: public only), in no order. */
    public List<ChunkRow> fetch(Collection<UUID> ids, @Nullable UUID userId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("ids", List.copyOf(ids));
        params.put("userId", userId);
        return jdbc.sql("""
                        SELECT c.id, c.heading_path, c.content, c.token_count, c.metadata ->> 'topic' AS topic_key,
                               d.title, d.source_path, d.metadata ->> 'technology' AS technology, d.doc_type
                        FROM knowledge_chunks c
                        JOIN knowledge_documents d ON d.id = c.document_id
                        WHERE c.id IN (:ids) AND d.status = 'READY'
                          AND (d.visibility = 'PUBLIC' OR d.owner_user_id = CAST(:userId AS uuid))""")
                .params(params)
                .query((rows, n) -> new ChunkRow(
                        rows.getObject("id", UUID.class), rows.getString("title"), rows.getString("source_path"),
                        rows.getString("technology"), DocumentType.valueOf(rows.getString("doc_type")),
                        rows.getString("heading_path"), rows.getString("content"), rows.getInt("token_count"),
                        rows.getString("topic_key")))
                .list();
    }

    /** A chunk and its document's description, as stored. */
    public record ChunkRow(UUID id, String title, String sourcePath, String technology, DocumentType docType,
            String headingPath, String content, int tokenCount, String topicKey) {
    }

    private static String filters(RetrievalQuery query, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("d.status = 'READY'");
        if (query.userId() == null) {
            sql.append(" AND d.visibility = 'PUBLIC'");
        } else {
            sql.append(" AND (d.visibility = 'PUBLIC' OR d.owner_user_id = :userId)");
            params.put("userId", query.userId());
        }
        if (!query.technologies().isEmpty()) {
            sql.append(" AND d.metadata ->> 'technology' IN (:technologies)");
            params.put("technologies", query.technologies().stream().map(Technology::slug).sorted().toList());
        }
        if (!query.docTypes().isEmpty()) {
            sql.append(" AND d.doc_type IN (:docTypes)");
            params.put("docTypes", query.docTypes().stream().map(Enum::name).sorted().toList());
        }
        if (query.difficulty() != null) {
            sql.append(" AND (c.difficulty IS NULL OR c.difficulty BETWEEN :minDifficulty AND :maxDifficulty)");
            params.put("minDifficulty", query.difficulty() - 1);
            params.put("maxDifficulty", query.difficulty() + 1);
        }
        return sql.toString();
    }

    /** pgvector's text form of a vector: {@code [0.1,0.2,...]}. */
    public static String literal(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}
