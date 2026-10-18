package com.techinterviewer.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
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

/** The Gemini provider against a local endpoint that speaks the generateContent REST format. */
class GeminiLlmProviderTest {

    private static final String KEY = "test-api-key-123";
    private static final JsonMapper JSON = TestJson.applicationMapper();

    private final StubHttpServer server = new StubHttpServer();
    private final GeminiLlmProvider gemini = new GeminiLlmProvider(
            new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(2)), JSON), JSON, URI.create(server.baseUrl() + "/v1beta/"), KEY, Clock.systemUTC());

    @AfterEach
    void stop() {
        server.close();
    }

    private static LlmRequest request(ThinkingLevel thinking) {
        JsonNode schema = JSON.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}");
        return new LlmRequest("gemini-3.8-flash", "You are a grader.", "Grade this.", new GenerationOptions(0.1, 2048, thinking),
                schema, Duration.ofSeconds(2));
    }

    private static final String SUCCESS = """
            {"candidates":[{"content":{"role":"model","parts":[
                {"text":"thinking out loud","thought":true},
                {"text":"{\\"a\\":"},{"text":"\\"b\\"}"}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":15,"thoughtsTokenCount":40,"totalTokenCount":175},
             "modelVersion":"gemini-3.8-flash-001"}""";

    @Test
    void theRequestUsesTheCurrentGenerateContentFormatAndTheKeyTravelsInAHeaderOnly() {
        server.reply(Reply.json(200, SUCCESS));

        gemini.generate(request(ThinkingLevel.LOW));

        StubHttpServer.Received sent = server.received().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1beta/models/gemini-3.8-flash:generateContent").doesNotContain(KEY);
        assertThat(sent.header("x-goog-api-key")).isEqualTo(KEY);
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.at("/systemInstruction/parts/0/text").asString()).isEqualTo("You are a grader.");
        assertThat(body.at("/contents/0/role").asString()).isEqualTo("user");
        assertThat(body.at("/contents/0/parts/0/text").asString()).isEqualTo("Grade this.");
        assertThat(body.at("/generationConfig/temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(2048);
        assertThat(body.at("/generationConfig/responseFormat/text/mimeType").asString()).isEqualTo("APPLICATION_JSON");
        assertThat(body.at("/generationConfig/responseFormat/text/schema/required/0").asString()).isEqualTo("a");
        assertThat(body.at("/generationConfig/thinkingConfig/thinkingLevel").asString()).isEqualTo("low");
        assertThat(body.at("/generationConfig").has("responseMimeType")).as("deprecated field").isFalse();
        assertThat(body.at("/generationConfig").has("responseJsonSchema")).as("deprecated field").isFalse();
    }

    @Test
    void theSchemaIsSentWithoutArrayBoundsButAForbiddenArrayStaysForbidden() {
        server.reply(Reply.json(200, SUCCESS));
        JsonNode schema = JSON.readTree("""
                {"type":"object","properties":{
                   "points":{"type":"array","minItems":3,"maxItems":6,"items":{"type":"object","properties":{
                      "sources":{"type":"array","maxItems":3,"items":{"type":"string"}}},"required":["sources"]}},
                   "repeats":{"type":"array","maxItems":0,"items":{"type":"string"}},
                   "maxItems":{"type":"integer","minimum":1}},
                 "required":["points","repeats","maxItems"]}""");

        gemini.generate(new LlmRequest("gemini-3.8-flash", "You are a grader.", "Grade this.", new GenerationOptions(0.1, 2048, ThinkingLevel.LOW),
                schema, Duration.ofSeconds(2)));

        JsonNode sent = JSON.readTree(server.received().getFirst().body()).at("/generationConfig/responseFormat/text/schema");
        assertThat(sent.at("/properties/points").has("minItems")).isFalse();
        assertThat(sent.at("/properties/points").has("maxItems")).isFalse();
        assertThat(sent.at("/properties/points/items/properties/sources").has("maxItems")).as("nested").isFalse();
        assertThat(sent.at("/properties/repeats/maxItems").asInt()).as("an array that must stay empty").isZero();
        assertThat(sent.at("/properties/maxItems/minimum").asInt()).as("a property that happens to be named maxItems").isEqualTo(1);
        assertThat(sent.at("/required")).isEqualTo(schema.at("/required"));
        assertThat(schema.at("/properties/points/maxItems").asInt()).as("the caller's schema is not changed").isEqualTo(6);
    }

    @Test
    void theModelsDefaultThinkingSendsNoThinkingConfigAndOffIsRejected() {
        server.reply(Reply.json(200, SUCCESS));

        gemini.generate(request(ThinkingLevel.PROVIDER_DEFAULT));

        assertThat(JSON.readTree(server.received().getFirst().body()).at("/generationConfig").has("thinkingConfig")).isFalse();
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.OFF))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAnswerIsTheTextOfTheFirstCandidateWithoutThoughtsAndUsageIsReported() {
        server.reply(Reply.json(200, SUCCESS));

        LlmResponse response = gemini.generate(request(ThinkingLevel.LOW));

        assertThat(response.text()).isEqualTo("{\"a\":\"b\"}");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage()).isEqualTo(new TokenUsage(120, 15, 40));
        assertThat(response.modelVersion()).isEqualTo("gemini-3.8-flash-001");
    }

    @Test
    void aTruncatedAnswerIsReportedAsCutOffByTheTokenLimit() {
        server.reply(Reply.json(200, """
                {"candidates":[{"content":{"parts":[{"text":"{\\"a\\": \\"unfini"}]},"finishReason":"MAX_TOKENS"}]}"""));

        LlmResponse response = gemini.generate(request(ThinkingLevel.LOW));

        assertThat(response.finishReason()).isEqualTo(FinishReason.MAX_TOKENS);
        assertThat(response.usage()).isEqualTo(TokenUsage.UNKNOWN);
    }

    @Test
    void aBlockedPromptAndAnAnswerStoppedForSafetyAreContentBlocks() {
        server.reply(Reply.json(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOf(ContentBlockedException.class).hasMessageContaining("SAFETY");

        for (String reason : new String[] {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII"}) {
            server.reply(Reply.json(200, "{\"candidates\":[{\"finishReason\":\"" + reason + "\"}]}"));
            assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                    .isInstanceOf(ContentBlockedException.class)
                    .satisfies(failure -> assertThat(((LlmException) failure).retryable()).isFalse());
        }
    }

    @Test
    void anUnusualFinishIsReportedAsOtherAndNoCandidateAsARetryableOutage() {
        server.reply(Reply.json(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]},\"finishReason\":\"RECITATION\"}]}"));
        assertThat(gemini.generate(request(ThinkingLevel.LOW)).finishReason()).isEqualTo(FinishReason.OTHER);

        server.reply(Reply.json(200, "{\"candidates\":[]}"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(failure -> assertThat(((LlmException) failure).retryable()).isTrue());
    }

    @Test
    void aRateLimitCarriesItsRetryAfter() {
        server.reply(Reply.json(429, "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}")
                .withHeader("Retry-After", "7"));

        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(RateLimitedException.class, limited -> {
                    assertThat(limited.retryAfter()).contains(Duration.ofSeconds(7));
                    assertThat(limited.retryable()).isTrue();
                    assertThat(limited.getMessage()).contains("429").contains("Resource has been exhausted");
                });
    }

    @Test
    void aRateLimitWithoutRetryAfterCarriesTheDelayGeminiPutsInItsBody() {
        // What Gemini sends: no Retry-After header, the delay in a google.rpc.RetryInfo entry of the error details.
        server.reply(Reply.json(429, """
                {"error":{"code":429,"message":"You exceeded your current quota. Please retry in 27.966937085s.","status":"RESOURCE_EXHAUSTED",
                 "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
                            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"27.966937085s"}]}}"""));

        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(RateLimitedException.class, limited ->
                        assertThat(limited.retryAfter()).contains(Duration.ofMillis(27_967)));
    }

    @Test
    void aRetryAfterHeaderWinsOverTheBodyAndAnUnreadableDelayIsIgnored() {
        server.reply(Reply.json(429, "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"30s\"}]}}")
                .withHeader("Retry-After", "5"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(RateLimitedException.class, limited -> assertThat(limited.retryAfter()).contains(Duration.ofSeconds(5)));

        for (String delay : List.of("soon", "-3s", "99999999s", "")) {
            server.reply(Reply.json(429, "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"" + delay + "\"}]}}"));
            assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW))).as(delay)
                    .isInstanceOfSatisfying(RateLimitedException.class, limited -> assertThat(limited.retryAfter()).isEmpty());
        }
    }

    @Test
    void serverErrorsAreRetryableAndRejectedRequestsAreNot() {
        server.reply(Reply.json(503, "{\"error\":{\"message\":\"The model is overloaded.\"}}"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isTrue());

        server.reply(Reply.json(400, "{\"error\":{\"message\":\"Invalid JSON payload received.\"}}"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(InvalidRequestException.class, failure -> {
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).contains("400").contains("Invalid JSON payload");
                });

        server.reply(Reply.json(403, "{\"error\":{\"message\":\"API key not valid. Please pass a valid API key.\"}}"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void aSlowAnswerTimesOutAtTheRequestsLimitBodyIncluded() {
        server.reply(Reply.json(200, SUCCESS).after(Duration.ofMillis(1500)));
        LlmRequest quick = request(ThinkingLevel.LOW).withTimeout(Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThatThrownBy(() -> gemini.generate(quick))
                .isInstanceOfSatisfying(LlmTimeoutException.class, failure -> assertThat(failure.retryable()).isTrue());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
    }

    @Test
    void anUnreachableProviderIsARetryableOutageAndANonJsonAnswerIsNot() {
        GeminiLlmProvider nowhere = new GeminiLlmProvider(new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(1)), JSON), JSON,
                URI.create("http://127.0.0.1:1"), KEY, Clock.systemUTC());
        assertThatThrownBy(() -> nowhere.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isTrue());

        server.reply(Reply.json(200, "<html>gateway</html>"));
        assertThatThrownBy(() -> gemini.generate(request(ThinkingLevel.LOW)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isFalse());
    }
}
