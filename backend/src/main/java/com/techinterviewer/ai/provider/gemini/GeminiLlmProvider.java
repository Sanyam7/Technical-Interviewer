package com.techinterviewer.ai.provider.gemini;

import com.techinterviewer.ai.provider.ContentBlockedException;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini through the REST {@code models/{model}:generateContent} endpoint (no SDK).
 *
 * <p>Request: the system prompt goes to {@code systemInstruction}, the task to one user turn; a JSON Schema is sent
 * as {@code generationConfig.responseFormat.text {mimeType: APPLICATION_JSON, schema}}, so the model is constrained
 * to it, without its array length bounds ({@link #withoutArrayBounds}); the thinking level goes to {@code generationConfig.thinkingConfig.thinkingLevel}. The API key travels in the
 * {@code x-goog-api-key} header, never in the URL, so it cannot leak into a log or an error message.
 *
 * <p>Response: the text parts of the first candidate (thought parts are skipped), its {@code finishReason} and the
 * {@code usageMetadata} counts. A blocked prompt ({@code promptFeedback.blockReason}) or a candidate stopped for
 * safety is a {@link ContentBlockedException}.
 */
public final class GeminiLlmProvider implements LlmProvider {

    public static final String ID = "gemini";

    /** Finish reasons that mean the content was refused, not that generation went wrong. */
    private static final Set<String> SAFETY_STOPS = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII");

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final URI baseUrl;
    private final String apiKey;
    private final Clock clock;

    public GeminiLlmProvider(ProviderHttp http, ObjectMapper json, URI baseUrl, String apiKey, Clock clock) {
        this.http = http;
        this.json = json;
        this.baseUrl = Require.nonNull(baseUrl, "baseUrl");
        this.apiKey = Require.notBlank(apiKey, "apiKey");
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Instant start = clock.instant();
        URI uri = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/models/"
                + URLEncoder.encode(request.model(), StandardCharsets.UTF_8) + ":generateContent");
        JsonNode response = http.postJson(ID, uri, Map.of("x-goog-api-key", apiKey), body(request), request.timeout());
        Duration latency = Duration.between(start, clock.instant());
        return read(response, latency);
    }

    ObjectNode body(LlmRequest request) {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
        ObjectNode turn = body.putArray("contents").addObject();
        turn.put("role", "user");
        turn.putArray("parts").addObject().put("text", request.userPrompt());
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", request.options().temperature());
        config.put("maxOutputTokens", request.options().maxOutputTokens());
        if (request.responseSchema() != null) {
            ObjectNode text = config.putObject("responseFormat").putObject("text");
            text.put("mimeType", "APPLICATION_JSON");
            text.set("schema", withoutArrayBounds(request.responseSchema()));
        }
        ThinkingLevel thinking = request.options().thinking();
        if (thinking == ThinkingLevel.OFF) {
            throw new IllegalArgumentException("Gemini 3 models cannot switch thinking off; use LOW");
        }
        if (thinking != ThinkingLevel.PROVIDER_DEFAULT) {
            config.putObject("thinkingConfig").put("thinkingLevel", thinking.name().toLowerCase(java.util.Locale.ROOT));
        }
        return body;
    }

    /**
     * The schema as Gemini receives it: a copy without array length bounds ({@code minItems}, and {@code maxItems} above 0).
     * Gemini refuses a schema whose constrained decoding would grow too large with HTTP 400 "Request contains an invalid
     * argument", and the bounds are what make ours large: the résumé schema is refused with them and accepted without them
     * (AI_ARCHITECTURE.md section 5). The bounds are still enforced after parsing, by {@code @Size} on the output records and by
     * the agents' checks. {@code maxItems: 0} forbids an array (nothing to cite, no earlier mistake to repeat) rather than
     * bounding it, and stays.
     */
    public static JsonNode withoutArrayBounds(JsonNode schema) {
        JsonNode copy = schema.deepCopy();
        removeArrayBounds(copy);
        return copy;
    }

    private static void removeArrayBounds(JsonNode schema) {
        if (!(schema instanceof ObjectNode object)) {
            return;
        }
        object.remove("minItems");
        JsonNode maxItems = object.path("maxItems");
        if (!(maxItems.isIntegralNumber() && maxItems.asInt() == 0)) {
            object.remove("maxItems");
        }
        // The keys of "properties" are names, not keywords: only its values are schemas.
        for (JsonNode property : object.path("properties")) {
            removeArrayBounds(property);
        }
        removeArrayBounds(object.path("items"));
        removeArrayBounds(object.path("additionalProperties"));
    }

    private static LlmResponse read(JsonNode response, Duration latency) {
        String blockReason = response.path("promptFeedback").path("blockReason").asString("");
        if (!blockReason.isBlank()) {
            throw new ContentBlockedException("Gemini blocked the prompt: " + blockReason);
        }
        JsonNode candidate = response.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            throw new ProviderUnavailableException("Gemini returned no candidate", true);
        }
        String finish = candidate.path("finishReason").asString("");
        if (SAFETY_STOPS.contains(finish)) {
            throw new ContentBlockedException("Gemini stopped the answer: " + finish);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.path("text").isString()) {
                text.append(part.path("text").asString());
            }
        }
        JsonNode usage = response.path("usageMetadata");
        return new LlmResponse(
                text.toString(),
                finishReason(finish),
                new TokenUsage(count(usage, "promptTokenCount"), count(usage, "candidatesTokenCount"), count(usage, "thoughtsTokenCount")),
                response.path("modelVersion").isString() ? response.path("modelVersion").asString() : null,
                latency);
    }

    private static FinishReason finishReason(String finish) {
        return switch (finish) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS" -> FinishReason.MAX_TOKENS;
            default -> FinishReason.OTHER;
        };
    }

    private static @Nullable Integer count(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() && value.asInt() >= 0 ? value.asInt() : null;
    }
}
