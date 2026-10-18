package com.techinterviewer.rag;

import com.techinterviewer.ai.observability.LlmCallRecorder;
import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.gemini.GeminiEmbeddingProvider;
import com.techinterviewer.ai.provider.ollama.OllamaEmbeddingProvider;
import com.techinterviewer.ai.routing.AiProperties;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.structured.Sleeper;
import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.evaluation.service.EvaluationProperties;
import com.techinterviewer.rag.chunking.MarkdownChunker;
import com.techinterviewer.rag.dedup.EmbeddingQuestionDeduplicator;
import com.techinterviewer.rag.dedup.QuestionDeduplicator;
import com.techinterviewer.rag.embedding.EmbeddingService;
import com.techinterviewer.rag.ingestion.ChunkEmbeddings;
import com.techinterviewer.rag.ingestion.CuratedCorpusLoader;
import com.techinterviewer.rag.ingestion.IngestionService;
import com.techinterviewer.rag.repository.KnowledgeChunkRepository;
import com.techinterviewer.rag.repository.KnowledgeDocumentRepository;
import com.techinterviewer.rag.retrieval.ChunkSearch;
import com.techinterviewer.rag.retrieval.HybridRetriever;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Retrieval-augmented generation (RAG_ARCHITECTURE.md), created when the AI layer is on and {@code app.rag.enabled}
 * is true (the default). Without it the agents get {@link KnowledgeRetriever#NONE} and work ungrounded.
 *
 * <pre>
 * startup:  knowledge-base/*.md ─▶ CorpusParser ─▶ MarkdownChunker ─▶ knowledge_chunks ─▶ EmbeddingService ─▶ embeddings
 * per task: agent ─▶ KnowledgeRetriever ─▶ HybridRetriever (vector + keyword, RRF) ─▶ ContextAssembler ─▶ prompt
 * </pre>
 *
 * Checked at startup: the embedding provider is configured, and the answer evaluation still finishes within its lease
 * when a query embedding takes its whole timeout (the evaluator retrieves before it calls the model).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.ai.enabled")
@ConditionalOnBooleanProperty(name = "app.rag.enabled", matchIfMissing = true)
public class RagConfiguration {

    @Bean
    EmbeddingService embeddingService(RagProperties rag, AiProperties ai, EvaluationProperties evaluation, ObjectMapper json,
            LlmCallRecorder recorder, Clock clock) {
        RagProperties.Embedding settings = rag.embedding();
        AiProperties.Route evaluationRoute = ai.routes().get(AiTask.ANSWER_EVALUATION);
        if (evaluationRoute != null) {
            Duration worstCase = evaluationRoute.budget().plus(settings.queryTimeout());
            if (worstCase.compareTo(evaluation.attemptLease()) >= 0) {
                throw new IllegalStateException("The answer-evaluation budget (" + evaluationRoute.budget() + ") plus the query embedding "
                        + "timeout (" + settings.queryTimeout() + ") must be shorter than app.evaluation.attempt-lease ("
                        + evaluation.attemptLease() + ")");
            }
        }
        ProviderHttp http = new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(10)), json);
        EmbeddingProvider provider = switch (settings.provider()) {
            case "gemini" -> {
                AiProperties.Gemini gemini = ai.providers().gemini();
                if (!gemini.configured()) {
                    throw new IllegalStateException("app.rag.embedding.provider is gemini but GEMINI_API_KEY is not set");
                }
                yield new GeminiEmbeddingProvider(http, json, gemini.baseUrl(), gemini.apiKey(), clock);
            }
            case "ollama" -> {
                AiProperties.Ollama ollama = ai.providers().ollama();
                if (!ollama.enabled()) {
                    throw new IllegalStateException("app.rag.embedding.provider is ollama but OLLAMA_ENABLED is false");
                }
                yield new OllamaEmbeddingProvider(http, json, ollama.baseUrl(), clock);
            }
            default -> throw new IllegalStateException("app.rag.embedding.provider must be gemini or ollama but was " + settings.provider());
        };
        return new EmbeddingService(provider, settings, recorder, clock, Sleeper.THREAD);
    }

    @Bean
    ChunkSearch chunkSearch(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return new ChunkSearch(jdbc, readOnly);
    }

    @Bean
    KnowledgeRetriever knowledgeRetriever(ChunkSearch search, EmbeddingService embeddings, RagProperties rag) {
        return new RagKnowledgeRetriever(new HybridRetriever(search, embeddings, rag.retrieval()), search);
    }

    @Bean
    QuestionDeduplicator questionDeduplicator(EmbeddingService embeddings, JdbcClient jdbc, RagProperties rag) {
        return new EmbeddingQuestionDeduplicator(embeddings, jdbc, rag.deduplication().minSimilarity());
    }

    @Bean
    ChunkEmbeddings chunkEmbeddings(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        return new ChunkEmbeddings(jdbc, new TransactionTemplate(transactionManager), clock);
    }

    @Bean
    CuratedCorpusLoader curatedCorpusLoader(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks,
            ChunkEmbeddings chunkEmbeddings, PlatformTransactionManager transactionManager, EmbeddingService embeddings,
            CatalogService catalog, RagProperties rag, Clock clock) {
        return new CuratedCorpusLoader(
                new IngestionService(documents, chunks, new MarkdownChunker(), new TransactionTemplate(transactionManager), clock),
                chunkEmbeddings, embeddings, catalog, rag.embedding().batchSize());
    }

    /** Loads the knowledge base once the application has started; idempotent, so a restart costs only hash checks. */
    @Bean
    @ConditionalOnBooleanProperty(name = "app.rag.load-corpus", matchIfMissing = true)
    ApplicationRunner knowledgeBaseLoader(CuratedCorpusLoader loader) {
        return arguments -> loader.load();
    }
}
