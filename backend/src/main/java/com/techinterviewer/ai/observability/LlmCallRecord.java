package com.techinterviewer.ai.observability;

import com.techinterviewer.common.validation.Require;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the structured-output client knows about one finished model call; the recorder stores it as an
 * {@link LlmCall}.
 *
 * @param interviewId   the interview the call served, if any
 * @param task          the AI task
 * @param provider      provider id
 * @param model         model name
 * @param modelVersion  exact version reported by the provider
 * @param promptId      prompt template
 * @param promptVersion its version
 * @param attempt       1 for the first call of an operation, then 2, 3, ... for retries and the repair
 * @param status        how it ended
 * @param finishReason  the normalised finish reason, when the provider answered
 * @param inputTokens   prompt tokens
 * @param outputTokens  answer tokens
 * @param thinkingTokens reasoning tokens
 * @param latency       wall-clock time of the call
 * @param errorCode     the failure's code, when it failed
 * @param errorMessage  the failure, provider details included (never a secret), when it failed or was rejected
 * @param ragChunkIds   the knowledge chunks the prompt contained, in reference order; empty when retrieval found
 *                      nothing and null when the call used no retrieval
 */
public record LlmCallRecord(
        @Nullable UUID interviewId,
        String task,
        String provider,
        String model,
        @Nullable String modelVersion,
        @Nullable String promptId,
        @Nullable String promptVersion,
        int attempt,
        LlmCallStatus status,
        @Nullable String finishReason,
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable Integer thinkingTokens,
        Duration latency,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Nullable List<UUID> ragChunkIds) {

    public LlmCallRecord {
        Require.notBlank(task, "task", 40);
        Require.notBlank(provider, "provider", 20);
        Require.notBlank(model, "model", 80);
        Require.atLeast(attempt, 1, "attempt");
        Require.nonNull(status, "status");
        Require.nonNull(latency, "latency");
        ragChunkIds = ragChunkIds == null ? null : List.copyOf(ragChunkIds);
    }
}
