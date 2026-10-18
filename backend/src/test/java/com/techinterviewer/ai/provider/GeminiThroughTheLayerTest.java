package com.techinterviewer.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.StubHttpServer;
import com.techinterviewer.ai.support.StubHttpServer.Reply;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.TopicRef;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The real Gemini provider, the real structured-output client and the real question agent against mocked Gemini
 * responses (no network, no key): what a caller sees for a valid answer, malformed JSON, a timeout, a rate limit, an
 * empty response and a model failure.
 */
class GeminiThroughTheLayerTest {

    private static final JsonMapper JSON = TestJson.applicationMapper();
    private static final String PROMPT = "Why can two threads that both run count++ on a shared int lose an update, and how would you prevent it?";
    private static final String QUESTION = """
            {"topic":"java/concurrency","difficulty":3,"prompt":"%s","estimatedMinutes":4,"expectedKeyPoints":[
             {"id":"kp1","text":"count++ is a read, an add and a write"},
             {"id":"kp2","text":"interleaved threads overwrite each other's write"},
             {"id":"kp3","text":"AtomicInteger or a lock makes it atomic"}]}""".formatted(PROMPT);

    private final StubHttpServer server = new StubHttpServer();

    @AfterEach
    void stop() {
        server.close();
    }

    private AiStack stack(Duration callTimeout, int maxRetries) {
        GeminiLlmProvider gemini = new GeminiLlmProvider(new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(2)), JSON),
                JSON, URI.create(server.baseUrl() + "/v1beta"), "test-api-key-123", Clock.systemUTC());
        return AiStack.around(gemini, callTimeout, maxRetries, Duration.ofSeconds(60));
    }

    private static GeneratedQuestion generate(AiStack stack) {
        QuestionRequest request = new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING, QuestionKind.TECHNICAL,
                new TopicRef("java", "Java", "concurrency", "Concurrency"), 3, TargetRole.BACKEND, ExperienceLevel.MID, null,
                List.of("Java"), List.of(), List.of(), null);
        return stack.questionGenerator().generate(request);
    }

    /** What Gemini wraps around the model's text. */
    private static Reply gemini(String text) {
        ObjectNode part = JSON.createObjectNode().put("text", text);
        ObjectNode candidate = JSON.createObjectNode().put("finishReason", "STOP");
        candidate.putObject("content").put("role", "model").putArray("parts").add(part);
        ObjectNode body = JSON.createObjectNode();
        body.putArray("candidates").add(candidate);
        body.putObject("usageMetadata").put("promptTokenCount", 500).put("candidatesTokenCount", 90);
        return Reply.json(200, body.toString());
    }

    private void answers(Reply... replies) {
        AtomicInteger next = new AtomicInteger();
        server.reply(request -> replies[Math.min(next.getAndIncrement(), replies.length - 1)]);
    }

    @Test
    void aValidResponseBecomesAGeneratedQuestionWithItsProvenance() {
        answers(gemini(QUESTION));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        GeneratedQuestion question = generate(stack);

        assertThat(question.prompt()).isEqualTo(PROMPT);
        assertThat(question.promptId()).isEqualTo("technical-question");
        assertThat(server.received()).hasSize(1);
        assertThat(server.received().getFirst().header("x-goog-api-key")).isEqualTo("test-api-key-123");
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.OK);
        assertThat(stack.recorder.records().getFirst().inputTokens()).isEqualTo(500);
    }

    @Test
    void malformedJsonIsSentBackOnceAndARepairedAnswerIsAccepted() {
        answers(gemini("{\"topic\": \"java/concurrency\", \"prompt\": "), gemini(QUESTION));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThat(generate(stack).prompt()).isEqualTo(PROMPT);

        assertThat(server.received()).hasSize(2);
        assertThat(server.received().getLast().body()).contains("rejected");
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.INVALID_OUTPUT, LlmCallStatus.REPAIRED);
    }

    @Test
    void malformedJsonTwiceIsAnUnavailableAiAndNeverAPartialQuestion() {
        answers(gemini("Sure! Here is your question:"));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThatThrownBy(() -> generate(stack)).isInstanceOf(AiUnavailableException.class);

        assertThat(server.received()).hasSize(2);
        assertThat(stack.recorder.statuses()).containsOnly(LlmCallStatus.INVALID_OUTPUT);
    }

    @Test
    void aTimeoutIsRecordedAndSurfacesAsUnavailable() {
        answers(gemini(QUESTION).after(Duration.ofSeconds(3)));
        AiStack stack = stack(Duration.ofMillis(300), 0);

        assertThatThrownBy(() -> generate(stack)).isInstanceOf(AiUnavailableException.class);

        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.TIMEOUT);
    }

    @Test
    void aRateLimitIsRetriedAfterTheDelayGeminiAskedFor() {
        answers(Reply.json(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}").withHeader("Retry-After", "2"),
                gemini(QUESTION));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThat(generate(stack).prompt()).isEqualTo(PROMPT);

        assertThat(server.received()).hasSize(2);
        assertThat(stack.sleeps).hasSize(1).allSatisfy(wait -> assertThat(wait).isGreaterThanOrEqualTo(Duration.ofSeconds(2)));
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.RATE_LIMITED, LlmCallStatus.OK);
    }

    @Test
    void aRateLimitIsRetriedAfterTheDelayGeminiPutsInTheErrorBody() {
        answers(Reply.json(429, """
                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
                 {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"12s"}]}}"""), gemini(QUESTION));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThat(generate(stack).prompt()).isEqualTo(PROMPT);

        assertThat(stack.sleeps).containsExactly(Duration.ofSeconds(12));
    }

    @Test
    void aRateLimitThatNeverLiftsStopsAfterTheRetryLimit() {
        answers(Reply.json(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThatThrownBy(() -> generate(stack)).isInstanceOf(AiUnavailableException.class);

        assertThat(server.received()).hasSize(3);
        assertThat(stack.recorder.statuses()).containsOnly(LlmCallStatus.RATE_LIMITED).hasSize(3);
    }

    @Test
    void anEmptyResponseIsNeverAQuestion() {
        for (Reply empty : List.of(gemini(""), Reply.json(200, "{\"candidates\":[]}"), Reply.json(200, "{}"))) {
            server.received().clear();
            answers(empty);
            AiStack stack = stack(Duration.ofSeconds(5), 0);

            assertThatThrownBy(() -> generate(stack)).as(empty.body()).isInstanceOf(AiUnavailableException.class);
        }
    }

    @Test
    void aModelFailureIsRetriedThenSurfacesAsUnavailable() {
        answers(Reply.json(500, "{\"error\":{\"code\":500,\"status\":\"INTERNAL\"}}"));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThatThrownBy(() -> generate(stack)).isInstanceOf(AiUnavailableException.class);

        assertThat(server.received()).hasSize(3);
        assertThat(stack.recorder.statuses()).containsOnly(LlmCallStatus.ERROR).hasSize(3);
    }

    @Test
    void aRejectedKeyOrRequestIsNotRetried() {
        answers(Reply.json(403, "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}"));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThatThrownBy(() -> generate(stack)).isInstanceOf(AiUnavailableException.class);

        assertThat(server.received()).hasSize(1);
    }

    @Test
    void aSafetyBlockIsNotRetriedAndIsRecordedAsBlocked() {
        answers(Reply.json(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));
        AiStack stack = stack(Duration.ofSeconds(5), 2);

        assertThatThrownBy(() -> generate(stack)).isInstanceOf(AiUnavailableException.class);

        assertThat(server.received()).hasSize(1);
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.BLOCKED);
    }
}
