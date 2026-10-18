package com.techinterviewer.ai.provider;

/**
 * What a text is embedded for. Retrieval models embed a stored passage and a search query differently, so the side
 * must be known: Gemini {@code gemini-embedding-001} takes it as a task type, newer models as a prefix of the text.
 */
public enum EmbeddingPurpose {
    /** A passage stored in the knowledge base. */
    DOCUMENT,
    /** A search query that looks for passages. */
    QUERY,
    /** A text compared with texts of its own kind (a question with earlier questions): both sides are embedded alike. */
    SIMILARITY
}
