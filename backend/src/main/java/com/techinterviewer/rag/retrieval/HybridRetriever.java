package com.techinterviewer.rag.retrieval;

import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.rag.RagProperties;
import com.techinterviewer.rag.embedding.EmbeddingService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hybrid retrieval: a vector search (meaning) and a keyword search (exact terms such as {@code ConcurrentHashMap} or
 * {@code SERIALIZABLE}, which embeddings tend to blur), merged by reciprocal rank fusion.
 *
 * <ol>
 *   <li>Embed the semantic text of every query in one call. If that fails, the vector half is skipped and retrieval
 *       continues with keywords only: degraded, not broken.
 *   <li>Per query, take the best {@code candidates} chunks of each half, with the query's filters.
 *   <li>Fuse the two rankings (k = {@code rrfK}), and add one first-place share ({@code 1 / (k + 1)}) to chunks of the
 *       query's topic: the topic the engine chose is strong evidence of relevance, but it only reorders, it does not
 *       exclude neighbouring material.
 *   <li>Drop chunks found only by the vector half whose similarity is below {@code minSimilarity}: a vector search
 *       always returns its nearest neighbours, however unrelated, and this is what lets an off-topic query come back
 *       empty. A keyword match is evidence by itself and is kept.
 *   <li>Return the best {@code limit} chunks.
 * </ol>
 */
public final class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final ChunkSearch search;
    private final EmbeddingService embeddings;
    private final RagProperties.Retrieval settings;

    public HybridRetriever(ChunkSearch search, EmbeddingService embeddings, RagProperties.Retrieval settings) {
        this.search = search;
        this.embeddings = embeddings;
        this.settings = settings;
    }

    public List<RetrievedChunk> retrieve(RetrievalQuery query, @Nullable UUID interviewId) {
        return retrieveAll(List.of(query), interviewId).getFirst();
    }

    /** One result list per query, in the order of the queries; the semantic texts are embedded in one call. */
    public List<List<RetrievedChunk>> retrieveAll(List<RetrievalQuery> queries, @Nullable UUID interviewId) {
        if (queries.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = null;
        try {
            vectors = embeddings.embedQueries(queries.stream().map(RetrievalQuery::semanticText).toList(), interviewId);
        } catch (LlmException failure) {
            log.warn("Query embedding failed ({}); retrieving by keywords only", failure.getMessage());
        }
        List<List<RetrievedChunk>> results = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            results.add(retrieve(queries.get(i), vectors == null ? null : vectors.get(i)));
        }
        return results;
    }

    private List<RetrievedChunk> retrieve(RetrievalQuery query, float @Nullable [] vector) {
        List<ChunkSearch.VectorHit> vectorHits = vector == null
                ? List.of() : search.vector(vector, embeddings.model(), query, settings.candidates());
        List<UUID> keywordHits = search.keyword(query.keywordTerms(), query, settings.candidates());
        Map<UUID, Double> similarity = vectorHits.stream()
                .collect(Collectors.toMap(ChunkSearch.VectorHit::chunkId, ChunkSearch.VectorHit::similarity, (a, b) -> a));
        List<ReciprocalRankFusion.Fused> fused = ReciprocalRankFusion.fuse(settings.rrfK(),
                List.of(vectorHits.stream().map(ChunkSearch.VectorHit::chunkId).toList(), keywordHits));
        if (fused.isEmpty()) {
            return List.of();
        }
        Map<UUID, ChunkSearch.ChunkRow> rows = search.fetch(fused.stream().map(ReciprocalRankFusion.Fused::id).toList(), query.userId())
                .stream().collect(Collectors.toMap(ChunkSearch.ChunkRow::id, Function.identity()));
        double topicBonus = 1.0 / (settings.rrfK() + 1);
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (ReciprocalRankFusion.Fused item : fused) {
            ChunkSearch.ChunkRow row = rows.get(item.id());
            Integer vectorRank = item.ranks()[0];
            Integer keywordRank = item.ranks()[1];
            Double cosine = similarity.get(item.id());
            if (row == null || (keywordRank == null && cosine != null && cosine < settings.minSimilarity())) {
                continue;
            }
            double score = item.score() + (query.topicKey() != null && query.topicKey().equals(row.topicKey()) ? topicBonus : 0);
            chunks.add(new RetrievedChunk(row.id(), row.title(), row.sourcePath(), row.technology(), row.docType(),
                    row.headingPath(), row.content(), row.tokenCount(), row.topicKey(), vectorRank, keywordRank, cosine, score));
        }
        chunks.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed().thenComparing(RetrievedChunk::chunkId));
        return chunks.stream().filter(Objects::nonNull).limit(query.limit()).toList();
    }
}
