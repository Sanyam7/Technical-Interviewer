package com.techinterviewer.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.AiIntegrationTestBase;
import com.techinterviewer.ai.support.HashingEmbedder;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.rag.RagProperties;
import com.techinterviewer.rag.Technology;
import com.techinterviewer.rag.context.KnowledgeContext;
import com.techinterviewer.rag.domain.DocumentContent;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import com.techinterviewer.rag.embedding.EmbeddingService;
import com.techinterviewer.rag.ingestion.ChunkEmbeddings;
import com.techinterviewer.rag.repository.KnowledgeChunkRepository;
import com.techinterviewer.rag.repository.KnowledgeDocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Hybrid retrieval against the loaded knowledge base on a real pgvector: relevant and irrelevant queries, the
 * technology, topic and difficulty dimensions, the keyword fallback when embedding fails, and that a private document
 * is only ever retrieved for its owner.
 */
class HybridRetrieverIT extends AiIntegrationTestBase {

    @Autowired
    ChunkSearch search;

    @Autowired
    EmbeddingService embeddingService;

    @Autowired
    RagProperties rag;

    @Autowired
    KnowledgeRetriever knowledge;

    @Autowired
    KnowledgeDocumentRepository documents;

    @Autowired
    KnowledgeChunkRepository chunks;

    @Autowired
    ChunkEmbeddings embeddings;

    private HybridRetriever retriever() {
        return new HybridRetriever(search, embeddingService, rag.retrieval());
    }

    private static RetrievalQuery query(String text, List<String> terms, Set<Technology> technologies, @Nullable String topic) {
        return new RetrievalQuery(text, terms, technologies, Set.of(), topic, null, null, 5);
    }

    @Test
    void aRelevantQueryFindsTheSectionThatAnswersItFirst() {
        List<RetrievedChunk> found = retriever().retrieve(query(
                "A volatile write happens-before every later read: visibility of a stop flag set by another thread",
                List.of("concurrency"), Set.of(Technology.JAVA), "java/concurrency"), null);

        assertThat(found).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(found.getFirst().headingPath()).isEqualTo("Java concurrency › The Java memory model and volatile");
        assertThat(found.getFirst().sourcePath()).isEqualTo("java/concurrency.md");
        assertThat(found.getFirst().vectorRank()).isNotNull();
        assertThat(found.getFirst().keywordRank()).isNotNull();
        assertThat(found).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void anIrrelevantQueryFindsNothing() {
        assertThat(retriever().retrieve(query("How long should sourdough bread ferment before baking?", List.of("sourdough", "bread"),
                Set.of(), null), null)).isEmpty();
    }

    @Test
    void theKeywordHalfFindsExactIdentifiers() {
        List<RetrievedChunk> found = retriever().retrieve(query("Several workers claim different pending rows of a jobs table",
                List.of("SKIP LOCKED"), Set.of(), null), null);

        assertThat(found).anySatisfy(chunk -> {
            assertThat(chunk.headingPath()).endsWith("Row locks and SELECT FOR UPDATE");
            assertThat(chunk.keywordRank()).isEqualTo(1);
        });
    }

    @Test
    void theTechnologyScopeKeepsOtherTechnologiesOut() {
        String text = "closures capture variables of the enclosing scope; the classic loop bug";
        assertThat(retriever().retrieve(query(text, List.of("closures"), Set.of(Technology.JAVASCRIPT), null), null))
                .isNotEmpty().allSatisfy(chunk -> assertThat(chunk.technology()).isEqualTo("javascript"));
        assertThat(retriever().retrieve(query(text, List.of("closures"), Set.of(Technology.GIT), null), null))
                .allSatisfy(chunk -> assertThat(chunk.technology()).isEqualTo("git"));
    }

    @Test
    void theTopicOfTheQuestionLiftsItsOwnChunks() {
        String text = "isolation and locking: what happens when two transactions update the same row";
        List<RetrievedChunk> neutral = retriever().retrieve(query(text, List.of("transactions"), Set.of(Technology.DATABASES, Technology.SQL), null), null);
        List<RetrievedChunk> topical = retriever().retrieve(
                query(text, List.of("transactions"), Set.of(Technology.DATABASES, Technology.SQL), "postgresql/locking-concurrency"), null);

        assertThat(topical.getFirst().topicKey()).isEqualTo("postgresql/locking-concurrency");
        assertThat(topical.getFirst().score()).isGreaterThan(neutral.stream()
                .filter(chunk -> chunk.chunkId().equals(topical.getFirst().chunkId())).findFirst().orElseThrow().score());
    }

    @Test
    void whenTheQueryCannotBeEmbeddedRetrievalFallsBackToKeywordsAndTheFailureIsRecorded() {
        MODEL.failEmbeddings(true);
        long failuresBefore = queryEmbeddingFailures();

        List<RetrievedChunk> found = retriever().retrieve(query("volatile visibility", List.of("volatile"), Set.of(Technology.JAVA), null), null);

        assertThat(found).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.vectorRank()).isNull();
            assertThat(chunk.keywordRank()).isNotNull();
        });
        assertThat(queryEmbeddingFailures()).isEqualTo(failuresBefore + 1);
    }

    private long queryEmbeddingFailures() {
        return jdbc.sql("SELECT count(*) FROM llm_calls WHERE task = 'QUERY_EMBEDDING' AND status = 'ERROR'").query(Long.class).single();
    }

    @Test
    void thePromptMaterialOfAGroundingIsAssembledFromTheSearchWithReferenceTokens() {
        KnowledgeContext context = knowledge.ground(new KnowledgeRetriever.Grounding(List.of(query(
                "Two threads increment a shared counter and lose updates", List.of("concurrency"), Set.of(Technology.JAVA), "java/concurrency")),
                List.of(), 1200, 3, null));

        assertThat(context.references()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(context.text()).startsWith("[r1] Java concurrency › ");
        assertThat(context.recordedChunkIds()).hasSize(context.references().size());
    }

    @Test
    void aPrivateDocumentIsRetrievedOnlyForItsOwnerAndTheDifficultyWindowApplies() {
        UUID owner = newUser();
        UUID stranger = newUser();
        String text = "Quokkaflux pipelines were migrated by the candidate to an event-driven design.";
        UUID chunkId = transaction.execute(status -> {
            KnowledgeDocument resume = documents.save(KnowledgeDocument.resume(owner,
                    new DocumentContent("Resume", "resume.md", "text/markdown", text.length(), "a".repeat(64), text), clock.instant()));
            resume.startProcessing(clock.instant());
            KnowledgeChunk chunk = chunks.save(new KnowledgeChunk(resume.getId(), 0, "Resume › Experience", text, 20, null, 5, Map.of(), clock.instant()));
            resume.markReady(1, clock.instant());
            return chunk.getId();
        });
        String embedded = "Resume › Experience\n\n" + text;
        embeddings.save(EMBEDDING_MODEL, List.of(new ChunkEmbeddings.Pending(chunkId, embedded)),
                List.of(HashingEmbedder.embed(embedded, RagProperties.DIMENSIONS)));

        RetrievalQuery forOwner = new RetrievalQuery("quokkaflux pipelines migration", List.of("quokkaflux"), Set.of(), Set.of(),
                null, 4, owner, 5);
        assertThat(retriever().retrieve(forOwner, null)).extracting(RetrievedChunk::chunkId).contains(chunkId);
        assertThat(retriever().retrieve(forOwner, null).getFirst().docType()).isEqualTo(DocumentType.RESUME);

        RetrievalQuery forStranger = new RetrievalQuery("quokkaflux pipelines migration", List.of("quokkaflux"), Set.of(), Set.of(),
                null, 4, stranger, 5);
        assertThat(retriever().retrieve(forStranger, null)).extracting(RetrievedChunk::chunkId).doesNotContain(chunkId);
        RetrievalQuery forNobody = new RetrievalQuery("quokkaflux pipelines migration", List.of("quokkaflux"), Set.of(), Set.of(),
                null, 4, null, 5);
        assertThat(retriever().retrieve(forNobody, null)).extracting(RetrievedChunk::chunkId).doesNotContain(chunkId);

        RetrievalQuery tooEasy = new RetrievalQuery("quokkaflux pipelines migration", List.of("quokkaflux"), Set.of(), Set.of(),
                null, 2, owner, 5);
        assertThat(retriever().retrieve(tooEasy, null)).as("difficulty 5 is outside 2 ± 1").extracting(RetrievedChunk::chunkId)
                .doesNotContain(chunkId);
    }
}
