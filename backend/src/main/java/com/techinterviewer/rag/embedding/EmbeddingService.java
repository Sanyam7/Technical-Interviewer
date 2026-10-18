package com.techinterviewer.rag.embedding;

import com.techinterviewer.ai.observability.LlmCallRecord;
import com.techinterviewer.ai.observability.LlmCallRecorder;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.EmbeddingPurpose;
import com.techinterviewer.ai.provider.EmbeddingRequest;
import com.techinterviewer.ai.provider.EmbeddingResponse;
import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.ai.provider.LlmTimeoutException;
import com.techinterviewer.ai.provider.ProviderUnavailableException;
import com.techinterviewer.ai.provider.RateLimitedException;
import com.techinterviewer.ai.structured.Sleeper;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.rag.RagProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Embeds texts with the configured model and records every call in {@code llm_calls} (tasks DOCUMENT_EMBEDDING,
 * QUERY_EMBEDDING and SIMILARITY_EMBEDDING), so the cost of retrieval is visible next to the cost of generation.
 *
 * <ul>
 *   <li>Knowledge-base passages are embedded in batches, and a batch is retried after a rate limit, an outage or a
 *       timeout with the same backoff as generation. This runs at startup, where waiting is acceptable.
 *   <li>Search queries are embedded once, with a short timeout and no retry: a query is embedded while a candidate
 *       waits, and retrieval can fall back to keyword search, so a second attempt is not worth the delay.
 *   <li>A question is embedded for de-duplication the same way (once, the query timeout, no retry): without its vector
 *       the lexical duplicate check still applies.
 * </ul>
 */
public final class EmbeddingService {

    public static final String DOCUMENT_TASK = "DOCUMENT_EMBEDDING";
    public static final String QUERY_TASK = "QUERY_EMBEDDING";
    public static final String SIMILARITY_TASK = "SIMILARITY_EMBEDDING";

    private final EmbeddingProvider provider;
    private final RagProperties.Embedding settings;
    private final LlmCallRecorder recorder;
    private final Clock clock;
    private final Sleeper sleeper;

    public EmbeddingService(EmbeddingProvider provider, RagProperties.Embedding settings, LlmCallRecorder recorder, Clock clock, Sleeper sleeper) {
        if (!provider.id().equals(settings.provider())) {
            throw new IllegalArgumentException("the embedding provider is " + settings.provider() + " but " + provider.id() + " was given");
        }
        this.provider = provider;
        this.settings = settings;
        this.recorder = recorder;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** The model whose vectors this service produces; stored with every vector and used to filter every search. */
    public String model() {
        return settings.model();
    }

    /** One vector per passage, in order. Batches as configured; each batch is retried as configured. */
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += settings.batchSize()) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + settings.batchSize()));
            vectors.addAll(embedWithRetries(batch));
        }
        return vectors;
    }

    /**
     * One vector per query, in order, in a single call that is not retried.
     *
     * @throws LlmException when the provider fails; the caller degrades to keyword search
     */
    public List<float[]> embedQueries(List<String> texts, @Nullable UUID interviewId) {
        return call(texts, EmbeddingPurpose.QUERY, settings.queryTimeout(), 1, interviewId);
    }

    /**
     * The vector of one text for comparison with texts of its kind, in one call that is not retried.
     *
     * @throws LlmException when the provider fails
     */
    public float[] embedForSimilarity(String text, @Nullable UUID interviewId) {
        return call(List.of(text), EmbeddingPurpose.SIMILARITY, settings.queryTimeout(), 1, interviewId).getFirst();
    }

    private List<float[]> embedWithRetries(List<String> batch) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call(batch, EmbeddingPurpose.DOCUMENT, settings.documentTimeout(), attempt, null);
            } catch (LlmException failure) {
                if (!failure.retryable() || attempt > settings.documentRetries()) {
                    throw failure;
                }
                try {
                    sleeper.sleep(StructuredOutputClient.backoff(attempt - 1, failure));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ProviderUnavailableException("interrupted while waiting to retry an embedding call", false, interrupted);
                }
            }
        }
    }

    private List<float[]> call(List<String> texts, EmbeddingPurpose purpose, Duration timeout, int attempt, @Nullable UUID interviewId) {
        String task = switch (purpose) {
            case QUERY -> QUERY_TASK;
            case DOCUMENT -> DOCUMENT_TASK;
            case SIMILARITY -> SIMILARITY_TASK;
        };
        Instant start = clock.instant();
        try {
            EmbeddingResponse response = provider.embed(
                    new EmbeddingRequest(settings.model(), texts, purpose, RagProperties.DIMENSIONS, timeout));
            record(interviewId, task, attempt, LlmCallStatus.OK, response.inputTokens(), response.latency(), null, null);
            return response.vectors();
        } catch (RuntimeException failure) {
            LlmCallStatus status = switch (failure) {
                case RateLimitedException ignored -> LlmCallStatus.RATE_LIMITED;
                case LlmTimeoutException ignored -> LlmCallStatus.TIMEOUT;
                default -> LlmCallStatus.ERROR;
            };
            String code = failure instanceof LlmException llm ? llm.code() : failure.getClass().getSimpleName();
            record(interviewId, task, attempt, status, null, Duration.between(start, clock.instant()), code, failure.getMessage());
            throw failure;
        }
    }

    private void record(@Nullable UUID interviewId, String task, int attempt, LlmCallStatus status, @Nullable Integer tokens,
            Duration latency, @Nullable String errorCode, @Nullable String error) {
        recorder.record(new LlmCallRecord(interviewId, task, provider.id(), settings.model(), null, null, null, attempt, status,
                null, tokens, null, null, latency, errorCode, error, null));
    }
}
