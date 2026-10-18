package com.techinterviewer.rag.dedup;

import com.techinterviewer.interview.port.QuestionEmbedding;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Semantic de-duplication of generated questions (AI_ARCHITECTURE.md section 2.9): a question is embedded, compared with
 * the earlier questions of its interview, and stored with its vector so later questions are compared with it. The
 * lexical check of the agents runs in any case; this one catches the same question in other words.
 */
public interface QuestionDeduplicator {

    /** Without an embedding model: nothing is embedded and nothing is a repeat, the lexical check stands alone. */
    QuestionDeduplicator NONE = new QuestionDeduplicator() {

        @Override
        public @Nullable QuestionEmbedding embed(UUID interviewId, String question) {
            return null;
        }

        @Override
        public Optional<Repeat> repeatOf(UUID interviewId, QuestionEmbedding embedding) {
            return Optional.empty();
        }
    };

    /** An earlier question of the interview that a new question repeats, and how similar the two are (cosine). */
    record Repeat(UUID questionId, double similarity) {
    }

    /** The question's vector; null when it could not be made (the question is then neither checked nor stored with one). */
    @Nullable QuestionEmbedding embed(UUID interviewId, String question);

    /** The most similar earlier question of the interview embedded by the same model, when it is similar enough to be a repeat. */
    Optional<Repeat> repeatOf(UUID interviewId, QuestionEmbedding embedding);
}
