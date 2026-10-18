package com.techinterviewer.ai.provider.ollama;

import com.techinterviewer.ai.provider.FinishReason;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.LlmRequest;
import com.techinterviewer.ai.provider.LlmResponse;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.ProviderUnavailableException;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.provider.TokenUsage;
import com.techinterviewer.common.validation.Require;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local model served by Ollama through {@code POST /api/chat} with {@code stream: false}. The JSON Schema goes to
 * {@code format}, which constrains the output to it; temperature and the output limit go to {@code options}
 * ({@code num_predict}), with the context window ({@code num_ctx}); the thinking level to {@code think} (off, or on for any level). Ollama has no safety
 * filter of its own, so it never reports blocked content.
 *
 * <p>Small local models follow a schema less reliably than Gemini; the structured-output client's validation and
 * repair attempt make that visible in {@code llm_calls} instead of silently degrading.
 */
public final class OllamaLlmProvider implements LlmProvider {

    public static final String ID = "ollama";
    public static final int DEFAULT_CONTEXT_WINDOW = 16_384;

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final URI chatUri;
    private final Clock clock;
    private final int contextWindow;

    /** A provider with the default context window. */
    public OllamaLlmProvider(ProviderHttp http, ObjectMapper json, URI baseUrl, Clock clock) {
        this(http, json, baseUrl, clock, DEFAULT_CONTEXT_WINDOW);
    }

    /**
     * @param contextWindow the tokens of prompt and answer together ({@code num_ctx}). Ollama's own default (4,096) silently cuts
     *                      the answer of a long prompt, such as a system design evaluation, and reports it as a length stop.
     */
    public OllamaLlmProvider(ProviderHttp http, ObjectMapper json, URI baseUrl, Clock clock, int contextWindow) {
        this.contextWindow = contextWindow;
        this.http = http;
        this.json = json;
        this.chatUri = URI.create(Require.nonNull(baseUrl, "baseUrl").toString().replaceAll("/+$", "") + "/api/chat");
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Instant start = clock.instant();
        JsonNode response = http.postJson(ID, chatUri, Map.of(), body(request), request.timeout());
        Duration latency = Duration.between(start, clock.instant());
        JsonNode content = response.path("message").path("content");
        if (!content.isString()) {
            throw new ProviderUnavailableException("Ollama returned no message content", false);
        }
        return new LlmResponse(
                content.asString(),
                "length".equals(response.path("done_reason").asString("")) ? FinishReason.MAX_TOKENS
                        : "stop".equals(response.path("done_reason").asString("")) ? FinishReason.STOP : FinishReason.OTHER,
                new TokenUsage(count(response, "prompt_eval_count"), count(response, "eval_count"), null),
                response.path("model").isString() ? response.path("model").asString() : null,
                latency);
    }

    ObjectNode body(LlmRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userPrompt());
        if (request.responseSchema() != null) {
            body.set("format", request.responseSchema());
        }
        ThinkingLevel thinking = request.options().thinking();
        if (thinking != ThinkingLevel.PROVIDER_DEFAULT) {
            body.put("think", thinking != ThinkingLevel.OFF);
        }
        ObjectNode options = body.putObject("options");
        options.put("temperature", request.options().temperature());
        options.put("num_predict", request.options().maxOutputTokens());
        options.put("num_ctx", contextWindow);
        return body;
    }

    private static @Nullable Integer count(JsonNode response, String field) {
        JsonNode value = response.path(field);
        return value.isIntegralNumber() && value.asInt() >= 0 ? value.asInt() : null;
    }
}
