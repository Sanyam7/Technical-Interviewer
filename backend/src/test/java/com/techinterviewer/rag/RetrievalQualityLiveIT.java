package com.techinterviewer.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.EmbeddingRequest;
import com.techinterviewer.ai.provider.EmbeddingResponse;
import com.techinterviewer.ai.provider.ProviderUnavailableException;
import com.techinterviewer.ai.support.CapturingCallRecorder;
import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.rag.embedding.EmbeddingService;
import com.techinterviewer.rag.ingestion.ChunkEmbeddings;
import com.techinterviewer.rag.retrieval.ChunkSearch;
import com.techinterviewer.rag.retrieval.HybridRetriever;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The retrieval evaluation set with a real embedding model, over the shipped knowledge base embedded at startup. Opt-in
 * (tag {@code live}): it needs Ollama on localhost with the model pulled, or a Gemini key.
 *
 * <pre>
 * ./mvnw verify -Dexcluded.test.groups= -Dgroups=live -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RetrievalQualityLiveIT
 * LIVE_EMBEDDING_PROVIDER=gemini LIVE_EMBEDDING_MODEL=gemini-embedding-2 GEMINI_API_KEY=... (same command) for Gemini
 * </pre>
 *
 * It measures the metrics at several similarity thresholds, so {@code app.rag.retrieval.min-similarity} can be chosen
 * for the model from data: the lowest threshold that keeps abstention on off-topic queries high without losing recall.
 */
@Tag("live")
class RetrievalQualityLiveIT extends InterviewApiTestBase {

    private static final Logger log = LoggerFactory.getLogger(RetrievalQualityLiveIT.class);
    private static final String PROVIDER = System.getenv().getOrDefault("LIVE_EMBEDDING_PROVIDER", "ollama");
    private static final String MODEL = System.getenv().getOrDefault("LIVE_EMBEDDING_MODEL", "embeddinggemma");
    private static final String OLLAMA = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

    @BeforeAll
    static void theModelIsAvailable() {
        if (PROVIDER.equals("gemini")) {
            assumeTrue(System.getenv("GEMINI_API_KEY") != null, "GEMINI_API_KEY is not set");
            return;
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            String tags = http.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assumeTrue(tags.contains("\"" + MODEL), "Ollama does not have " + MODEL + " (ollama pull " + MODEL + ")");
        } catch (Exception unreachable) {
            assumeTrue(false, "Ollama is not reachable at " + OLLAMA);
        }
    }

    @DynamicPropertySource
    static void realEmbeddings(DynamicPropertyRegistry registry) {
        registry.add("app.ai.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.base-url", () -> OLLAMA);
        if (PROVIDER.equals("gemini")) {
            registry.add("app.ai.providers.gemini.api-key", () -> System.getenv("GEMINI_API_KEY"));
        }
        for (String task : List.of("question-generation", "follow-up-generation", "answer-evaluation", "report-writing", "resume-extraction")) {
            registry.add("app.ai.routes." + task + ".provider", () -> "ollama");
            registry.add("app.ai.routes." + task + ".model", () -> "unused");
        }
        registry.add("app.rag.embedding.provider", () -> PROVIDER);
        registry.add("app.rag.embedding.model", () -> MODEL);
        registry.add("app.rag.embedding.document-timeout", () -> "300s");
        registry.add("app.rag.embedding.batch-size", () -> "32");
        registry.add("app.rag.embedding.query-timeout", () -> "180s");
        registry.add("app.evaluation.attempt-lease", () -> "300s");
        registry.add("app.ai.routes.answer-evaluation.budget", () -> "60s");
    }

    @Autowired
    ChunkSearch search;

    @Autowired
    EmbeddingService embeddings;

    @Autowired
    ChunkEmbeddings chunkEmbeddings;

    @Autowired
    RagProperties rag;

    @Test
    void theLabelledSetWithARealEmbeddingModelAcrossSimilarityThresholdsAndAblations() {
        assertThat(chunkEmbeddings.countMissing(MODEL)).as("the knowledge base is embedded with " + MODEL).isZero();
        List<RetrievalEvaluation.Case> cases = RetrievalEvaluation.load();
        StringBuilder table = new StringBuilder("\n| retrieval | min-similarity | recall@5 | hit@5 | MRR@5 | abstention |\n|---|---|---|---|---|---|\n");
        for (RetrievalEvaluation.Mode mode : RetrievalEvaluation.Mode.values()) {
            for (double threshold : new double[] {0.0, 0.30, 0.35, 0.40, 0.45, 0.50}) {
                RetrievalEvaluation.Report report = RetrievalEvaluation.run(
                        String.format(Locale.ROOT, "%s-%s-%s-%.2f", PROVIDER, MODEL.replace(':', '_'), mode, threshold), cases,
                        new HybridRetriever(search, embeddings, settings(threshold)), mode);
                RetrievalEvaluation.write(report);
                row(table, mode.name(), threshold, report);
                if (mode == RetrievalEvaluation.Mode.HYBRID && threshold == 0.0) {
                    report.cases().forEach(result -> log.info("{} first relevant at {} (top similarity {}): {}", result.id(),
                            result.firstRelevantRank(), result.topSimilarity(), result.retrieved().stream().limit(2).toList()));
                }
            }
        }
        EmbeddingService broken = new EmbeddingService(new FailingModel(), new RagProperties.Embedding(PROVIDER, MODEL, 1,
                Duration.ofSeconds(1), 0, Duration.ofSeconds(1)), new CapturingCallRecorder(), java.time.Clock.systemUTC(), wait -> { });
        row(table, "KEYWORD_ONLY", 0, RetrievalEvaluation.run(PROVIDER + "-keyword-only", cases, new HybridRetriever(search, broken, settings(0))));
        log.info("Retrieval with {} / {}:{}", PROVIDER, MODEL, table);
    }

    private RagProperties.Retrieval settings(double threshold) {
        return new RagProperties.Retrieval(rag.retrieval().candidates(), rag.retrieval().rrfK(), threshold);
    }

    private static void row(StringBuilder table, String mode, double threshold, RetrievalEvaluation.Report report) {
        table.append(String.format(Locale.ROOT, "| %s | %.2f | %.3f | %.3f | %.3f | %.3f |%n",
                mode, threshold, report.recallAt5(), report.hitAt5(), report.mrrAt5(), report.abstention()));
    }

    /** An embedding model that is down: the retriever then searches by keywords only. */
    private static final class FailingModel implements EmbeddingProvider {
        @Override
        public String id() {
            return PROVIDER;
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            throw new ProviderUnavailableException("down for the keyword-only ablation", false);
        }
    }
}
