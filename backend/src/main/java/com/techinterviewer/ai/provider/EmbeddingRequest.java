package com.techinterviewer.ai.provider;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.time.Duration;
import java.util.List;

/**
 * Texts to embed in one call, independent of the provider.
 *
 * @param model      provider model name, for example {@code gemini-embedding-2}
 * @param texts      1 to {@value #MAX_TEXTS} non-blank texts; the result has one vector per text, in the same order
 * @param purpose    stored passages or a search query
 * @param dimensions the length every returned vector must have
 * @param timeout    the longest this call may take, connection included
 */
public record EmbeddingRequest(String model, List<String> texts, EmbeddingPurpose purpose, int dimensions, Duration timeout) {

    public static final int MAX_TEXTS = 100;

    public EmbeddingRequest {
        Require.notBlank(model, "model", 80);
        texts = Immutables.list(texts);
        Require.between(texts.size(), 1, MAX_TEXTS, "texts size");
        texts.forEach(text -> Require.notBlank(text, "text"));
        Require.nonNull(purpose, "purpose");
        Require.between(dimensions, 1, 4096, "dimensions");
        Require.nonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive but was " + timeout);
        }
    }
}
