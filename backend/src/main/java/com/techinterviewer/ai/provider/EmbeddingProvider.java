package com.techinterviewer.ai.provider;

/**
 * A model that turns texts into vectors for semantic search (RAG_ARCHITECTURE.md). Implementations translate one
 * request into the provider's API, check the answer (one vector per text, the requested length, finite values) and
 * return unit vectors, so cosine similarity is a dot product and vectors of every provider are comparable in form.
 * Vectors of different models are never comparable in meaning: the caller keeps them apart by model name.
 *
 * <p>Failures are the same {@link LlmException}s as for generation: a rate limit, a timeout, an outage, a rejected
 * request.
 */
public interface EmbeddingProvider {

    /** The provider id used in configuration, for example {@code gemini}. */
    String id();

    EmbeddingResponse embed(EmbeddingRequest request);
}
