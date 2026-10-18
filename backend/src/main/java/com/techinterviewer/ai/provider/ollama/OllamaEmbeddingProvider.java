package com.techinterviewer.ai.provider.ollama;

import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.EmbeddingRequest;
import com.techinterviewer.ai.provider.EmbeddingResponse;
import com.techinterviewer.ai.provider.EmbeddingVectors;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.common.validation.Require;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local embedding model through Ollama's {@code POST /api/embed}: all texts in one call as {@code input}, each with
 * the task prefix its model expects ({@link EmbeddingVectors#withTaskPrefix}). The answer's {@code embeddings} are
 * checked and normalised; {@code prompt_eval_count} is the token count.
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final URI baseUrl;
    private final Clock clock;

    public OllamaEmbeddingProvider(ProviderHttp http, ObjectMapper json, URI baseUrl, Clock clock) {
        this.http = http;
        this.json = json;
        this.baseUrl = Require.nonNull(baseUrl, "baseUrl");
        this.clock = clock;
    }

    @Override
    public String id() {
        return OllamaLlmProvider.ID;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        Instant start = clock.instant();
        URI uri = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/api/embed");
        JsonNode response = http.postJson(id(), uri, Map.of(), body(request), request.timeout());
        List<JsonNode> arrays = new ArrayList<>();
        response.path("embeddings").forEach(arrays::add);
        JsonNode tokens = response.path("prompt_eval_count");
        return new EmbeddingResponse(EmbeddingVectors.read(id(), arrays, request),
                tokens.isNumber() ? tokens.asInt() : null, Duration.between(start, clock.instant()));
    }

    ObjectNode body(EmbeddingRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        ArrayNode input = body.putArray("input");
        request.texts().forEach(text -> input.add(EmbeddingVectors.withTaskPrefix(request.model(), request.purpose(), text)));
        return body;
    }
}
