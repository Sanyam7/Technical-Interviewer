package com.techinterviewer.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestClock;
import com.techinterviewer.ai.observability.LlmCallRecord;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.EmbeddingPurpose;
import com.techinterviewer.ai.provider.EmbeddingRequest;
import com.techinterviewer.ai.provider.EmbeddingResponse;
import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.ai.provider.LlmTimeoutException;
import com.techinterviewer.ai.provider.ProviderUnavailableException;
import com.techinterviewer.ai.provider.RateLimitedException;
import com.techinterviewer.ai.support.CapturingCallRecorder;
import com.techinterviewer.ai.support.HashingEmbedder;
import com.techinterviewer.rag.RagProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Batching, retries, the no-retry rule for queries and the call records of embedding. */
class EmbeddingServiceTest {

    /** A provider that answers with hashed vectors, after the failures it was given. */
    private static final class Model implements EmbeddingProvider {
        final List<EmbeddingRequest> requests = new ArrayList<>();
        final Deque<LlmException> failures = new ArrayDeque<>();

        @Override
        public String id() {
            return "ollama";
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            requests.add(request);
            if (!failures.isEmpty()) {
                throw failures.pop();
            }
            return new EmbeddingResponse(HashingEmbedder.embedAll(request.texts(), request.dimensions()), 12, Duration.ofMillis(40));
        }
    }

    private final Model model = new Model();
    private final CapturingCallRecorder recorder = new CapturingCallRecorder();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RagProperties.Embedding settings =
            new RagProperties.Embedding("ollama", "embeddinggemma", 3, Duration.ofSeconds(60), 2, Duration.ofSeconds(5));
    private final EmbeddingService service =
            new EmbeddingService(model, settings, recorder, new TestClock(Instant.parse("2026-10-09T10:00:00Z")), sleeps::add);

    private static List<String> texts(int count) {
        return IntStream.range(0, count).mapToObj(i -> "passage " + i).toList();
    }

    @Test
    void passagesAreEmbeddedInBatchesInOrderAsDocuments() {
        List<float[]> vectors = service.embedDocuments(texts(7));

        assertThat(vectors).hasSize(7);
        assertThat(vectors.get(5)).containsExactly(HashingEmbedder.embed("passage 5", RagProperties.DIMENSIONS));
        assertThat(model.requests).extracting(request -> request.texts().size()).containsExactly(3, 3, 1);
        assertThat(model.requests).allSatisfy(request -> {
            assertThat(request.purpose()).isEqualTo(EmbeddingPurpose.DOCUMENT);
            assertThat(request.dimensions()).isEqualTo(768);
            assertThat(request.model()).isEqualTo("embeddinggemma");
            assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(60));
        });
        assertThat(recorder.records()).hasSize(3).allSatisfy(record -> {
            assertThat(record.task()).isEqualTo(EmbeddingService.DOCUMENT_TASK);
            assertThat(record.status()).isEqualTo(LlmCallStatus.OK);
            assertThat(record.provider()).isEqualTo("ollama");
            assertThat(record.inputTokens()).isEqualTo(12);
            assertThat(record.ragChunkIds()).isNull();
        });
    }

    @Test
    void aFailedBatchIsRetriedWithBackoffUpToTheLimit() {
        model.failures.add(new RateLimitedException("slow down", Duration.ofSeconds(4)));
        model.failures.add(new ProviderUnavailableException("503", true));

        assertThat(service.embedDocuments(texts(2))).hasSize(2);

        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.getFirst()).isEqualTo(Duration.ofSeconds(4));
        assertThat(recorder.statuses()).containsExactly(LlmCallStatus.RATE_LIMITED, LlmCallStatus.ERROR, LlmCallStatus.OK);
        assertThat(recorder.records()).extracting(LlmCallRecord::attempt).containsExactly(1, 2, 3);
    }

    @Test
    void aBatchStopsRetryingAtTheLimitOrAtAFailureThatWouldRepeat() {
        for (int i = 0; i < 3; i++) {
            model.failures.add(new LlmTimeoutException("slow"));
        }
        assertThatThrownBy(() -> service.embedDocuments(texts(1))).isInstanceOf(LlmTimeoutException.class);
        assertThat(model.requests).hasSize(3);

        model.requests.clear();
        model.failures.add(new ProviderUnavailableException("wrong dimensions", false));
        assertThatThrownBy(() -> service.embedDocuments(texts(1))).isInstanceOf(ProviderUnavailableException.class);
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void queriesAreEmbeddedInOneCallWithTheShortTimeoutAndNeverRetried() {
        UUID interview = UUID.randomUUID();
        assertThat(service.embedQueries(List.of("a", "b"), interview)).hasSize(2);
        assertThat(model.requests.getFirst().purpose()).isEqualTo(EmbeddingPurpose.QUERY);
        assertThat(model.requests.getFirst().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(recorder.records().getFirst().interviewId()).isEqualTo(interview);
        assertThat(recorder.records().getFirst().task()).isEqualTo(EmbeddingService.QUERY_TASK);

        model.failures.add(new ProviderUnavailableException("503", true));
        assertThatThrownBy(() -> service.embedQueries(List.of("a"), interview)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(model.requests).hasSize(2);
        assertThat(sleeps).isEmpty();
        assertThat(recorder.records().getLast().status()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(recorder.records().getLast().errorCode()).isNotBlank();
    }

    @Test
    void aQuestionIsEmbeddedForSimilarityOnceWithTheShortTimeoutAndRecordedUnderItsOwnTask() {
        UUID interview = UUID.randomUUID();

        assertThat(service.embedForSimilarity("What does volatile guarantee?", interview)).isNotNull();

        assertThat(model.requests).singleElement().satisfies(request -> {
            assertThat(request.purpose()).isEqualTo(EmbeddingPurpose.SIMILARITY);
            assertThat(request.texts()).containsExactly("What does volatile guarantee?");
            assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(5));
        });
        assertThat(recorder.records().getFirst().task()).isEqualTo(EmbeddingService.SIMILARITY_TASK);
        assertThat(recorder.records().getFirst().interviewId()).isEqualTo(interview);

        model.failures.add(new ProviderUnavailableException("503", true));
        assertThatThrownBy(() -> service.embedForSimilarity("again", interview)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(model.requests).as("never retried").hasSize(2);
    }

    @Test
    void theProviderMustBeTheConfiguredOne() {
        RagProperties.Embedding gemini = new RagProperties.Embedding("gemini", "gemini-embedding-2", 50, Duration.ofSeconds(60), 2, Duration.ofSeconds(5));
        assertThatThrownBy(() -> new EmbeddingService(model, gemini, recorder, new TestClock(Instant.EPOCH), sleeps::add))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
