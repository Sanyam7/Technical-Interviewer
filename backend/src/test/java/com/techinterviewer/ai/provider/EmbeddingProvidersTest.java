package com.techinterviewer.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.provider.gemini.GeminiEmbeddingProvider;
import com.techinterviewer.ai.provider.ollama.OllamaEmbeddingProvider;
import com.techinterviewer.ai.support.StubHttpServer;
import com.techinterviewer.ai.support.StubHttpServer.Reply;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The embedding providers against local endpoints that speak the Gemini and Ollama formats (mocked responses). */
class EmbeddingProvidersTest {

    private static final JsonMapper JSON = TestJson.applicationMapper();
    private static final String KEY = "test-api-key-123";

    private final StubHttpServer server = new StubHttpServer();
    private final ProviderHttp http = new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(2)), JSON);
    private final GeminiEmbeddingProvider gemini =
            new GeminiEmbeddingProvider(http, JSON, URI.create(server.baseUrl() + "/v1beta"), KEY, Clock.systemUTC());
    private final OllamaEmbeddingProvider ollama = new OllamaEmbeddingProvider(http, JSON, server.baseUrl(), Clock.systemUTC());

    @AfterEach
    void stop() {
        server.close();
    }

    private static EmbeddingRequest request(String model, EmbeddingPurpose purpose, String... texts) {
        return new EmbeddingRequest(model, List.of(texts), purpose, 4, Duration.ofSeconds(2));
    }

    private static final String GEMINI_TWO_VECTORS = """
            {"embeddings":[{"values":[3,0,0,4]},{"values":[0,2,0,0]}]}""";

    @Test
    void geminiEmbedsAllTextsInOneBatchCallWithTheTaskAsATextPrefixAndTheKeyInAHeader() {
        server.reply(Reply.json(200, GEMINI_TWO_VECTORS));

        EmbeddingResponse response = gemini.embed(request("gemini-embedding-2", EmbeddingPurpose.QUERY, "volatile", "happens-before"));

        StubHttpServer.Received sent = server.received().getFirst();
        assertThat(sent.path()).isEqualTo("/v1beta/models/gemini-embedding-2:batchEmbedContents").doesNotContain(KEY);
        assertThat(sent.header("x-goog-api-key")).isEqualTo(KEY);
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.at("/requests").size()).isEqualTo(2);
        assertThat(body.at("/requests/0/model").asString()).isEqualTo("models/gemini-embedding-2");
        assertThat(body.at("/requests/0/content/parts/0/text").asString()).isEqualTo("task: search result | query: volatile");
        assertThat(body.at("/requests/1/outputDimensionality").asInt()).isEqualTo(4);
        assertThat(body.at("/requests/0").has("taskType")).as("gemini-embedding-2 takes no task type").isFalse();
        assertThat(response.vectors()).hasSize(2);
        assertThat(response.vectors().getFirst()).containsExactly(0.6f, 0f, 0f, 0.8f);
        assertThat(response.vectors().get(1)).containsExactly(0f, 1f, 0f, 0f);
    }

    @Test
    void geminiEmbedding001GetsTheTaskTypeInsteadOfAPrefix() {
        server.reply(Reply.json(200, "{\"embeddings\":[{\"values\":[1,0,0,0]}]}"));

        gemini.embed(request("gemini-embedding-001", EmbeddingPurpose.DOCUMENT, "A passage."));

        JsonNode body = JSON.readTree(server.received().getFirst().body());
        assertThat(body.at("/requests/0/taskType").asString()).isEqualTo("RETRIEVAL_DOCUMENT");
        assertThat(body.at("/requests/0/content/parts/0/text").asString()).isEqualTo("A passage.");
    }

    @Test
    void ollamaEmbedsThroughApiEmbedWithTheModelsPrefixAndReportsTokens() {
        server.reply(Reply.json(200, "{\"model\":\"embeddinggemma\",\"embeddings\":[[0,0,5,0]],\"prompt_eval_count\":7}"));

        EmbeddingResponse response = ollama.embed(request("embeddinggemma", EmbeddingPurpose.DOCUMENT, "A passage."));

        StubHttpServer.Received sent = server.received().getFirst();
        assertThat(sent.path()).isEqualTo("/api/embed");
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.get("model").asString()).isEqualTo("embeddinggemma");
        assertThat(body.at("/input/0").asString()).isEqualTo("title: none | text: A passage.");
        assertThat(response.vectors().getFirst()).containsExactly(0f, 0f, 1f, 0f);
        assertThat(response.inputTokens()).isEqualTo(7);
    }

    @Test
    void theTaskPrefixFollowsTheModelFamily() {
        assertThat(EmbeddingVectors.withTaskPrefix("nomic-embed-text", EmbeddingPurpose.QUERY, "q")).isEqualTo("search_query: q");
        assertThat(EmbeddingVectors.withTaskPrefix("nomic-embed-text:v1.5", EmbeddingPurpose.DOCUMENT, "d")).isEqualTo("search_document: d");
        assertThat(EmbeddingVectors.withTaskPrefix("embeddinggemma:300m", EmbeddingPurpose.QUERY, "q")).isEqualTo("task: search result | query: q");
        assertThat(EmbeddingVectors.withTaskPrefix("mxbai-embed-large", EmbeddingPurpose.QUERY, "q")).isEqualTo("q");
        assertThat(EmbeddingVectors.withTaskPrefix("embeddinggemma", EmbeddingPurpose.SIMILARITY, "s"))
                .isEqualTo("task: sentence similarity | query: s");
        assertThat(EmbeddingVectors.withTaskPrefix("gemini-embedding-2", EmbeddingPurpose.SIMILARITY, "s"))
                .isEqualTo("task: sentence similarity | query: s");
        assertThat(EmbeddingVectors.withTaskPrefix("nomic-embed-text", EmbeddingPurpose.SIMILARITY, "s")).isEqualTo("clustering: s");
        assertThat(EmbeddingVectors.withTaskPrefix("mxbai-embed-large", EmbeddingPurpose.SIMILARITY, "s")).isEqualTo("s");
    }

    @Test
    void geminiEmbedding001ComparesQuestionsWithTheSemanticSimilarityTaskType() {
        server.reply(Reply.json(200, "{\"embeddings\":[{\"values\":[1,0,0,0]}]}"));

        gemini.embed(request("gemini-embedding-001", EmbeddingPurpose.SIMILARITY, "A question."));

        JsonNode body = JSON.readTree(server.received().getFirst().body());
        assertThat(body.at("/requests/0/taskType").asString()).isEqualTo("SEMANTIC_SIMILARITY");
    }

    @Test
    void returnedVectorsAreUnitLength() {
        server.reply(Reply.json(200, "{\"embeddings\":[{\"values\":[0.1,0.2,0.3,0.4]}]}"));

        float[] vector = gemini.embed(request("gemini-embedding-2", EmbeddingPurpose.QUERY, "x")).vectors().getFirst();

        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        assertThat(norm).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void anAnswerWithTheWrongNumberOfVectorsOrTheWrongLengthOrABadValueIsRejectedAndNotRetried() {
        for (String body : List.of(
                "{\"embeddings\":[]}",
                "{}",
                "{\"embeddings\":[{\"values\":[1,0,0]}]}",
                "{\"embeddings\":[{\"values\":[1,0,0,\"x\"]}]}",
                "{\"embeddings\":[{\"values\":[0,0,0,0]}]}")) {
            server.reply(Reply.json(200, body));

            assertThatThrownBy(() -> gemini.embed(request("gemini-embedding-2", EmbeddingPurpose.QUERY, "x")))
                    .as(body)
                    .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isFalse());
        }
    }

    @Test
    void aRateLimitAnOutageAndATimeoutAreTheUsualProviderFailures() {
        server.reply(Reply.json(429, "{}").withHeader("Retry-After", "3"));
        assertThatThrownBy(() -> ollama.embed(request("embeddinggemma", EmbeddingPurpose.QUERY, "x")))
                .isInstanceOfSatisfying(RateLimitedException.class, limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(3)));

        server.reply(Reply.json(503, "{\"error\":\"overloaded\"}"));
        assertThatThrownBy(() -> gemini.embed(request("gemini-embedding-2", EmbeddingPurpose.QUERY, "x")))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isTrue());

        server.reply(Reply.json(200, GEMINI_TWO_VECTORS).after(Duration.ofSeconds(3)));
        assertThatThrownBy(() -> gemini.embed(new EmbeddingRequest("gemini-embedding-2", List.of("x"), EmbeddingPurpose.QUERY, 4,
                Duration.ofMillis(300)))).isInstanceOf(LlmTimeoutException.class);
    }

    @Test
    void aRequestIsValidatedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> new EmbeddingRequest("m", List.of(), EmbeddingPurpose.QUERY, 4, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingRequest("m", List.of(" "), EmbeddingPurpose.QUERY, 4, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
