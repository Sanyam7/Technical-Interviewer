package com.techinterviewer.interview.domain;

/** How a question text came to be. The constants equal the {@code ck_questions_source} check. */
public enum QuestionOrigin {
    /** Written by the question generator. */
    GENERATED,
    /** A curated exemplar used because generation failed validation or the provider was down. */
    EXEMPLAR_FALLBACK,
    /** Deterministic text built by the server (the introduction). */
    STATIC
}
