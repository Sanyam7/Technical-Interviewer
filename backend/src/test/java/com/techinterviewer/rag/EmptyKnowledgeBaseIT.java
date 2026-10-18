package com.techinterviewer.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.AiIntegrationTestBase;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.rag.context.KnowledgeContext;
import com.techinterviewer.rag.ingestion.CuratedCorpusLoader;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * An application whose knowledge base is empty (the corpus is not loaded; its own database): retrieval finds nothing,
 * nothing needs embedding, and the agents still work, ungrounded, recording that retrieval found nothing.
 */
@TestPropertySource(properties = "app.rag.load-corpus=false")
class EmptyKnowledgeBaseIT extends AiIntegrationTestBase {

    @Autowired
    KnowledgeRetriever knowledge;

    @Autowired
    CuratedCorpusLoader loader;

    @Autowired
    QuestionGenerator questions;

    @Test
    void retrievalFindsNothingAndThereIsNothingToEmbed() {
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunks").query(Long.class).single()).isZero();

        KnowledgeContext context = knowledge.ground(new KnowledgeRetriever.Grounding(List.of(new RetrievalQuery(
                "volatile visibility happens-before", List.of("volatile"), Set.of(Technology.JAVA), Set.of(), "java/concurrency", 3, null, 5)),
                List.of(UUID.randomUUID()), 1500, 5, null));

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.retrieved()).isTrue();
        assertThat(context.text()).isEqualTo(KnowledgeContext.NO_MATERIAL);
        assertThat(loader.embedMissing()).isZero();
    }

    @Test
    void aQuestionIsStillGeneratedUngroundedAndItsCallRecordsThatNothingWasFound() throws Exception {
        UUID interview = startedInterview(newUser());

        GeneratedQuestion generated = questions.generate(new QuestionRequest(interview, Section.QUESTIONING, QuestionKind.TECHNICAL,
                new TopicRef("java", "Java", "concurrency", "Concurrency"), 3, TargetRole.BACKEND, ExperienceLevel.MID, null,
                List.of("Java"), List.of(), List.of(), null));

        assertThat(generated.groundingChunkIds()).isEmpty();
        assertThat(generated.expectedKeyPoints()).allSatisfy(point -> assertThat(point.sourceChunkIds()).isEmpty());
        UUID call = generated.llmCallId();
        assertThat(jdbc.sql("SELECT cardinality(rag_chunk_ids) FROM llm_calls WHERE id = ?").param(call).query(Integer.class).single())
                .as("retrieval ran and found nothing: an empty list, not null").isZero();
    }
}
