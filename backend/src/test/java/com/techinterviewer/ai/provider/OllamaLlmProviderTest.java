package com.techinterviewer.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.provider.ollama.OllamaLlmProvider;
import com.techinterviewer.ai.support.StubHttpServer;
import com.techinterviewer.ai.support.StubHttpServer.Reply;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Ollama provider against a local endpoint that speaks the /api/chat format. */
class OllamaLlmProviderTest {

    private static final JsonMapper JSON = TestJson.applicationMapper();

    private final StubHttpServer server = new StubHttpServer();
    private final OllamaLlmProvider ollama = new OllamaLlmProvider(
            new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(2)), JSON), JSON, server.baseUrl(), Clock.systemUTC());

    @AfterEach
    void stop() {
        server.close();
    }

    private static LlmRequest request(ThinkingLevel thinking) {
        return new LlmRequest("qwen3.5:4b", "You are a grader.", "Grade this.", new GenerationOptions(0.2, 1024, thinking),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}"), Duration.ofSeconds(2));
    }

    private static final String SUCCESS = """
            {"model":"qwen3.5:4b","message":{"role":"assistant","content":"{\\"a\\":\\"b\\"}","thinking":"hmm"},
             "done":true,"done_reason":"stop","prompt_eval_count":300,"eval_count":42}""";

    @Test
    void theRequestIsANonStreamingChatWithTheSchemaAsFormat() {
        server.reply(Reply.json(200, SUCCESS));

        ollama.generate(request(ThinkingLevel.OFF));

        StubHttpServer.Received sent = server.received().getFirst();
        assertThat(sent.path()).isEqualTo("/api/chat");
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.get("model").asString()).isEqualTo("qwen3.5:4b");
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.at("/messages/0/role").asString()).isEqualTo("system");
        assertThat(body.at("/messages/1/content").asString()).isEqualTo("Grade this.");
        assertThat(body.at("/format/type").asString()).isEqualTo("object");
        assertThat(body.get("think").asBoolean()).isFalse();
        assertThat(body.at("/options/temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.at("/options/num_predict").asInt()).isEqualTo(1024);
        assertThat(body.at("/options/num_ctx").asInt()).as("a context window large enough for long prompts").isEqualTo(16_384);
    }

    @Test
    void thinkingIsSwitchedOnForAnyLevelAndLeftToTheModelByDefault() {
        server.reply(Reply.json(200, SUCCESS));

        ollama.generate(request(ThinkingLevel.MEDIUM));
        ollama.generate(request(ThinkingLevel.PROVIDER_DEFAULT));

        assertThat(JSON.readTree(server.received().get(0).body()).get("think").asBoolean()).isTrue();
        assertThat(JSON.readTree(server.received().get(1).body()).has("think")).isFalse();
    }

    @Test
    void theAnswerIsTheMessageContentWithoutTheThinking() {
        server.reply(Reply.json(200, SUCCESS));

        LlmResponse response = ollama.generate(request(ThinkingLevel.OFF));

        assertThat(response.text()).isEqualTo("{\"a\":\"b\"}");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage()).isEqualTo(new TokenUsage(300, 42, null));
        assertThat(response.modelVersion()).isEqualTo("qwen3.5:4b");
    }

    @Test
    void theLengthLimitIsReportedAsCutOff() {
        server.reply(Reply.json(200, "{\"message\":{\"content\":\"{\\\"a\\\": \\\"unf\"},\"done_reason\":\"length\"}"));

        assertThat(ollama.generate(request(ThinkingLevel.OFF)).finishReason()).isEqualTo(FinishReason.MAX_TOKENS);
    }

    @Test
    void anUnknownModelIsARejectedRequestAndAServerFailureAnOutage() {
        server.reply(Reply.json(404, "{\"error\":\"model 'qwen3.5:4b' not found, try pulling it first\"}"));
        assertThatThrownBy(() -> ollama.generate(request(ThinkingLevel.OFF)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not found");

        server.reply(Reply.json(500, "{\"error\":\"llama runner process has terminated\"}"));
        assertThatThrownBy(() -> ollama.generate(request(ThinkingLevel.OFF)))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isTrue());
    }

    @Test
    void anAnswerWithoutMessageContentIsUnusable() {
        server.reply(Reply.json(200, "{\"done\":true}"));

        assertThatThrownBy(() -> ollama.generate(request(ThinkingLevel.OFF))).isInstanceOf(ProviderUnavailableException.class);
    }
}
