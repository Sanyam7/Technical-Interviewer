package com.techinterviewer.ai.provider.gemini;

import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.EmbeddingRequest;
import com.techinterviewer.ai.provider.EmbeddingResponse;
import com.techinterviewer.ai.provider.EmbeddingVectors;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.common.validation.Require;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * Gemini embeddings through the REST {@code models/{model}:batchEmbedContents} endpoint: every text of the request in
 * one call, each with {@code outputDimensionality} set to the requested length. {@code gemini-embedding-001} is told
 * the purpose through {@code taskType} (RETRIEVAL_DOCUMENT, RETRIEVAL_QUERY or SEMANTIC_SIMILARITY); newer models take it as a prefix of the
 * text ({@link EmbeddingVectors#withTaskPrefix}). Vectors shorter than the model's native size are not unit length for
 * every model, so they are normalised. The key travels in the {@code x-goog-api-key} header.
 */
public final class GeminiEmbeddingProvider implements EmbeddingProvider {

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final URI baseUrl;
    private final String apiKey;
    private final Clock clock;

    public GeminiEmbeddingProvider(ProviderHttp http, ObjectMapper json, URI baseUrl, String apiKey, Clock clock) {
        this.http = http;
        this.json = json;
        this.baseUrl = Require.nonNull(baseUrl, "baseUrl");
        this.apiKey = Require.notBlank(apiKey, "apiKey");
        this.clock = clock;
    }

    @Override
    public String id() {
        return GeminiLlmProvider.ID;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        Instant start = clock.instant();
        URI uri = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/models/"
                + URLEncoder.encode(request.model(), StandardCharsets.UTF_8) + ":batchEmbedContents");
        JsonNode response = http.postJson(id(), uri, Map.of("x-goog-api-key", apiKey), body(request), request.timeout());
        List<JsonNode> arrays = new ArrayList<>();
        response.path("embeddings").forEach(embedding -> arrays.add(embedding.path("values")));
        return new EmbeddingResponse(EmbeddingVectors.read(id(), arrays, request), null, Duration.between(start, clock.instant()));
    }

    ObjectNode body(EmbeddingRequest request) {
        boolean taskType = request.model().startsWith("gemini-embedding-001");
        ObjectNode body = json.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        for (String text : request.texts()) {
            ObjectNode item = requests.addObject();
            item.put("model", "models/" + request.model());
            String content = taskType ? text : EmbeddingVectors.withTaskPrefix(request.model(), request.purpose(), text);
            item.putObject("content").putArray("parts").addObject().put("text", content);
            item.put("outputDimensionality", request.dimensions());
            if (taskType) {
                item.put("taskType", switch (request.purpose()) {
                    case QUERY -> "RETRIEVAL_QUERY";
                    case DOCUMENT -> "RETRIEVAL_DOCUMENT";
                    case SIMILARITY -> "SEMANTIC_SIMILARITY";
                });
            }
        }
        return body;
    }
}
