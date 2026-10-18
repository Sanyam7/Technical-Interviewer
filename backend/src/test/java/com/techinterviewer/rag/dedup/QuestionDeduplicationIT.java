package com.techinterviewer.rag.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.AiIntegrationTestBase;
import com.techinterviewer.interview.port.QuestionEmbedding;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Semantic de-duplication against the real database (DATABASE_SCHEMA.md section 4.2): questions are embedded through the
 * configured provider (the test model behind the Ollama protocol), stored in {@code questions.embedding}, and a new
 * question is compared with the earlier questions of its own interview embedded by the same model only.
 *
 * <p>The test model's embedding knows words, not meaning, so these cases use identical or unrelated wording; how real
 * embeddings separate paraphrases from different questions on the same topic is measured by
 * {@code QuestionDuplicatesLiveIT} on {@code ai-evals/datasets/question-duplicates.jsonl}.
 */
class QuestionDeduplicationIT extends AiIntegrationTestBase {

    private static final String VOLATILE = "Why can two threads that both run count++ on a volatile int still lose an update?";
    private static final String INDEXES = "When does PostgreSQL choose a bitmap heap scan over an index scan for a range query?";

    @Autowired
    QuestionDeduplicator deduplicator;

    @Autowired
    InterviewQuestionRepository questions;

    @AfterEach
    void embeddingsWork() {
        MODEL.failEmbeddings(false);
    }

    private UUID storedQuestion(UUID interview, int sequence, String prompt, QuestionEmbedding embedding) {
        UUID id = jdbc.sql("""
                        INSERT INTO questions (id, interview_id, sequence_no, section, kind, difficulty, prompt, rubric_id, source)
                        VALUES (gen_random_uuid(), :interview, :sequence, 'QUESTIONING', 'TECHNICAL', 3, :prompt, 'technical.v1', 'GENERATED')
                        RETURNING id""")
                .param("interview", interview).param("sequence", sequence).param("prompt", prompt)
                .query(UUID.class).single();
        transaction.executeWithoutResult(status -> questions.storeEmbedding(id, embedding.literal(), embedding.model()));
        return id;
    }

    @Test
    void theConfiguredModelEmbedsTheQuestionAndAnEarlierQuestionOfTheInterviewIsFoundAgain() throws Exception {
        UUID interview = startedInterview(newUser());
        QuestionEmbedding stored = deduplicator.embed(interview, VOLATILE);
        assertThat(stored).isNotNull();
        assertThat(stored.model()).isEqualTo(EMBEDDING_MODEL);
        UUID earlier = storedQuestion(interview, 2, VOLATILE, stored);

        assertThat(deduplicator.repeatOf(interview, deduplicator.embed(interview, VOLATILE))).hasValueSatisfying(repeat -> {
            assertThat(repeat.questionId()).isEqualTo(earlier);
            assertThat(repeat.similarity()).isGreaterThan(0.99);
        });
        assertThat(deduplicator.repeatOf(interview, deduplicator.embed(interview, INDEXES))).as("a different question").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM llm_calls WHERE interview_id = :id AND task = 'SIMILARITY_EMBEDDING'")
                .param("id", interview).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void onlyQuestionsOfTheSameInterviewEmbeddedByTheSameModelAreCompared() throws Exception {
        UUID interview = startedInterview(newUser());
        UUID other = startedInterview(newUser());
        QuestionEmbedding embedding = deduplicator.embed(interview, VOLATILE);
        storedQuestion(other, 2, VOLATILE, embedding);
        storedQuestion(interview, 2, VOLATILE, new QuestionEmbedding("another-model", embedding.vector()));

        assertThat(deduplicator.repeatOf(interview, embedding)).as("same text in another interview, or by another model").isEmpty();
        assertThat(deduplicator.repeatOf(other, embedding)).isPresent();
    }

    @Test
    void aQuestionThatCannotBeEmbeddedIsLeftToTheLexicalCheck() throws Exception {
        UUID interview = startedInterview(newUser());
        MODEL.failEmbeddings(true);

        assertThat(deduplicator.embed(interview, VOLATILE)).isNull();
        assertThat(jdbc.sql("SELECT status FROM llm_calls WHERE interview_id = :id AND task = 'SIMILARITY_EMBEDDING'")
                .param("id", interview).query(String.class).single()).isEqualTo("ERROR");
    }

    @Test
    void theStoredVectorKeepsItsModelAndTheStaticIntroductionHasNone() throws Exception {
        UUID interview = startedInterview(newUser());
        UUID id = storedQuestion(interview, 2, VOLATILE, deduplicator.embed(interview, VOLATILE));

        assertThat(jdbc.sql("SELECT embedding_model FROM questions WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo(EMBEDDING_MODEL);
        assertThat(jdbc.sql("SELECT vector_dims(embedding) FROM questions WHERE id = :id").param("id", id).query(Integer.class).single())
                .isEqualTo(768);
        assertThat(jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = :id AND embedding IS NULL AND kind = 'INTRO'")
                .param("id", interview).query(Long.class).single()).isEqualTo(1);
    }
}
