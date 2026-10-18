package com.techinterviewer.ai.provider;

import com.techinterviewer.common.collect.Immutables;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The vectors of one embedding call, one per requested text and in the same order, each of unit length.
 *
 * @param vectors     unit vectors of the requested dimensions
 * @param inputTokens tokens the provider counted, when it reports them
 * @param latency     time the call took
 */
public record EmbeddingResponse(List<float[]> vectors, @Nullable Integer inputTokens, Duration latency) {

    public EmbeddingResponse {
        vectors = Immutables.list(vectors);
    }
}
