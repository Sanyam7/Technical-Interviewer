package com.techinterviewer.ai.provider;

import com.techinterviewer.common.validation.Require;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What a provider returned for one request. Content-safety refusals never arrive here: they are thrown as
 * {@link ContentBlockedException}.
 *
 * @param text         the generated text (the JSON document for a structured request); may be empty
 * @param finishReason why generation stopped
 * @param usage        token counts as reported by the provider
 * @param modelVersion the exact model version, when the provider reports one
 * @param latency      wall-clock time of the call
 */
public record LlmResponse(
        String text,
        FinishReason finishReason,
        TokenUsage usage,
        @Nullable String modelVersion,
        Duration latency) {

    public LlmResponse {
        Require.nonNull(text, "text");
        Require.nonNull(finishReason, "finishReason");
        Require.nonNull(usage, "usage");
        Require.nonNull(latency, "latency");
    }
}
