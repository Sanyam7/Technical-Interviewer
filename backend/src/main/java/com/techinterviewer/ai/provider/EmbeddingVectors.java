package com.techinterviewer.ai.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * What every embedding provider shares: how a model expects to be told the purpose of a text, and the checks and
 * normalisation of the vectors it returns.
 */
public final class EmbeddingVectors {

    private EmbeddingVectors() {
    }

    /**
     * The text as the model expects it. Models that take their task as part of the text get the prefix their authors
     * document: {@code gemini-embedding-2} and {@code embeddinggemma} use "task: search result | query: " for a query,
     * "title: none | text: " for a passage and "task: sentence similarity | query: " for a similarity comparison;
     * {@code nomic-embed-text} uses "search_query: ", "search_document: " and "clustering: ". Other models get the text
     * unchanged.
     */
    public static String withTaskPrefix(String model, EmbeddingPurpose purpose, String text) {
        String name = model.toLowerCase(Locale.ROOT);
        if (name.startsWith("gemini-embedding-2") || name.startsWith("embeddinggemma")) {
            return switch (purpose) {
                case QUERY -> "task: search result | query: ";
                case DOCUMENT -> "title: none | text: ";
                case SIMILARITY -> "task: sentence similarity | query: ";
            } + text;
        }
        if (name.startsWith("nomic-embed-text")) {
            return switch (purpose) {
                case QUERY -> "search_query: ";
                case DOCUMENT -> "search_document: ";
                case SIMILARITY -> "clustering: ";
            } + text;
        }
        return text;
    }

    /**
     * Reads the vectors of a provider answer and returns them at unit length.
     *
     * @throws ProviderUnavailableException (not retryable) when the count, a length or a value is wrong: the same
     *                                      request would get the same wrong answer
     */
    public static List<float[]> read(String provider, List<JsonNode> arrays, EmbeddingRequest request) {
        if (arrays.size() != request.texts().size()) {
            throw invalid(provider, "returned " + arrays.size() + " vectors for " + request.texts().size() + " texts");
        }
        List<float[]> vectors = new ArrayList<>(arrays.size());
        for (JsonNode array : arrays) {
            if (!array.isArray() || array.size() != request.dimensions()) {
                throw invalid(provider, "returned a vector of " + (array.isArray() ? array.size() : 0)
                        + " dimensions instead of " + request.dimensions() + " (model " + request.model() + ")");
            }
            float[] vector = new float[array.size()];
            double norm = 0;
            for (int i = 0; i < vector.length; i++) {
                JsonNode value = array.get(i);
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                    throw invalid(provider, "returned a vector with a value that is not a finite number");
                }
                vector[i] = (float) value.asDouble();
                norm += vector[i] * (double) vector[i];
            }
            if (norm == 0) {
                throw invalid(provider, "returned a zero vector");
            }
            double length = Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / length);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static ProviderUnavailableException invalid(String provider, String detail) {
        return new ProviderUnavailableException(provider + " " + detail, false);
    }
}
