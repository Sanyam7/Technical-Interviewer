package com.techinterviewer.ai.observability;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One model call ({@code llm_calls}, AI_ARCHITECTURE.md section 8): what was asked of which model with which prompt,
 * how long it took, how many tokens it used and how it ended. Immutable once written. Questions, evaluations and
 * reports reference the call that produced them.
 *
 * <p>Not mapped yet: the user id, the cost estimate, the RAG chunk ids (Phase 3) and the opt-in request and response
 * payloads (Phase 8). The columns exist and stay null.
 */
@Entity
@Table(name = "llm_calls")
public class LlmCall extends BaseEntity {

    public static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    @Column(name = "interview_id", updatable = false)
    private @Nullable UUID interviewId;

    @Column(name = "trace_id", length = 64, updatable = false)
    private @Nullable String traceId;

    @Column(name = "task", nullable = false, length = 40, updatable = false)
    private String task;

    @Column(name = "provider", nullable = false, length = 20, updatable = false)
    private String provider;

    @Column(name = "model", nullable = false, length = 80, updatable = false)
    private String model;

    @Column(name = "model_version", length = 80, updatable = false)
    private @Nullable String modelVersion;

    @Column(name = "prompt_id", length = 60, updatable = false)
    private @Nullable String promptId;

    @Column(name = "prompt_version", length = 20, updatable = false)
    private @Nullable String promptVersion;

    @Column(name = "attempt", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private LlmCallStatus status;

    @Column(name = "finish_reason", length = 20, updatable = false)
    private @Nullable String finishReason;

    @Column(name = "input_tokens", updatable = false)
    private @Nullable Integer inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private @Nullable Integer outputTokens;

    @Column(name = "thinking_tokens", updatable = false)
    private @Nullable Integer thinkingTokens;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private int latencyMs;

    @Column(name = "error_code", length = 60, updatable = false)
    private @Nullable String errorCode;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH, updatable = false)
    private @Nullable String errorMessage;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "rag_chunk_ids", updatable = false, columnDefinition = "uuid[]")
    private UUID @Nullable [] ragChunkIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LlmCall() {
    }

    public LlmCall(LlmCallRecord record, @Nullable String traceId, Instant createdAt) {
        Require.nonNull(record, "record");
        this.interviewId = record.interviewId();
        this.traceId = traceId == null || traceId.isBlank() ? null : truncate(traceId, 64);
        this.task = record.task();
        this.provider = record.provider();
        this.model = record.model();
        this.modelVersion = record.modelVersion() == null ? null : truncate(record.modelVersion(), 80);
        this.promptId = record.promptId();
        this.promptVersion = record.promptVersion();
        this.attempt = record.attempt();
        this.status = record.status();
        this.finishReason = record.finishReason();
        this.inputTokens = record.inputTokens();
        this.outputTokens = record.outputTokens();
        this.thinkingTokens = record.thinkingTokens();
        this.latencyMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, record.latency().toMillis()));
        this.errorCode = record.errorCode();
        this.errorMessage = record.errorMessage() == null ? null : truncate(record.errorMessage(), MAX_ERROR_MESSAGE_LENGTH);
        this.ragChunkIds = record.ragChunkIds() == null ? null : record.ragChunkIds().toArray(UUID[]::new);
        this.createdAt = Require.nonNull(createdAt, "createdAt");
    }

    /** The knowledge chunks the prompt contained; null when the call used no retrieval. */
    public @Nullable List<UUID> getRagChunkIds() {
        return ragChunkIds == null ? null : List.of(ragChunkIds);
    }

    private static String truncate(String value, int max) {
        // Control characters cannot be stored as text (U+0000) and have no business in a log row.
        String clean = value.replaceAll("\\p{Cntrl}", " ");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    public @Nullable UUID getInterviewId() {
        return interviewId;
    }

    public String getTask() {
        return task;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public @Nullable String getPromptId() {
        return promptId;
    }

    public @Nullable String getPromptVersion() {
        return promptVersion;
    }

    public int getAttempt() {
        return attempt;
    }

    public LlmCallStatus getStatus() {
        return status;
    }

    public @Nullable String getFinishReason() {
        return finishReason;
    }

    public @Nullable Integer getInputTokens() {
        return inputTokens;
    }

    public @Nullable Integer getOutputTokens() {
        return outputTokens;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public @Nullable String getErrorCode() {
        return errorCode;
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    public @Nullable String getTraceId() {
        return traceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
