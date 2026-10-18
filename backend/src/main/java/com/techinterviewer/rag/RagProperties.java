package com.techinterviewer.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retrieval-augmented generation ({@code app.rag}, RAG_ARCHITECTURE.md). Active only when the AI layer is on.
 *
 * @param enabled    ground the AI tasks in the knowledge base ({@code RAG_ENABLED}); off, every agent runs ungrounded
 * @param loadCorpus load the curated knowledge base at startup (idempotent: unchanged documents are skipped)
 * @param embedding  the embedding model
 * @param retrieval  search settings
 * @param deduplication when a generated question counts as a repeat of an earlier one
 */
@ConfigurationProperties("app.rag")
@Validated
public record RagProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean loadCorpus,
        @Valid @NotNull @DefaultValue Embedding embedding,
        @Valid @NotNull @DefaultValue Retrieval retrieval,
        @Valid @NotNull @DefaultValue Deduplication deduplication) {

    /** Every vector has this length: the {@code embeddings.embedding} column is {@code vector(768)}. */
    public static final int DIMENSIONS = 768;

    /**
     * The embedding model. Vectors of different models are never compared: switching the model re-embeds the
     * knowledge base at the next startup.
     *
     * @param provider        {@code gemini} or {@code ollama}; must be configured under {@code app.ai.providers}
     * @param model           provider model name
     * @param batchSize       texts per call when the knowledge base is embedded
     * @param documentTimeout longest call when the knowledge base is embedded
     * @param documentRetries extra attempts for a knowledge-base batch after a rate limit, an outage or a timeout
     * @param queryTimeout    longest call to embed a search query; a query is never retried: retrieval then falls
     *                        back to keyword search so the interview is not held up
     */
    public record Embedding(
            @NotBlank @DefaultValue("gemini") String provider,
            @NotBlank @DefaultValue("gemini-embedding-2") String model,
            @Min(1) @Max(100) @DefaultValue("50") int batchSize,
            @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 10) @DefaultValue("60s") Duration documentTimeout,
            @Min(0) @Max(5) @DefaultValue("2") int documentRetries,
            @NotNull @DurationMin(millis = 500) @DurationMax(minutes = 5) @DefaultValue("5s") Duration queryTimeout) {
    }

    /**
     * How chunks are found.
     *
     * @param candidates    chunks taken from each half of the hybrid search (vector and keyword) before fusion
     * @param rrfK          the constant of reciprocal rank fusion; 60 is the value of the original paper
     * @param minSimilarity cosine similarity a chunk found only by the vector search needs to be kept. It depends on
     *                      the embedding model and is calibrated with the retrieval evaluation set
     */
    public record Retrieval(
            @Min(1) @Max(200) @DefaultValue("20") int candidates,
            @Min(1) @Max(1000) @DefaultValue("60") int rrfK,
            @DecimalMin("-1.0") @DecimalMax("1.0") @DefaultValue("0.30") double minSimilarity) {
    }

    /**
     * Semantic de-duplication of questions (AI_ARCHITECTURE.md section 2.9).
     *
     * @param minSimilarity cosine similarity from which a new question counts as a repeat of an earlier question of the
     *                      interview. It depends on the embedding model and is calibrated with
     *                      {@code ai-evals/datasets/question-duplicates.jsonl}
     */
    public record Deduplication(@DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.90") double minSimilarity) {
    }
}
