package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import com.techinterviewer.rag.repository.KnowledgeChunkRepository;
import com.techinterviewer.rag.repository.KnowledgeDocumentRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What the interview stores of a generated question, and what it tells the generator about the questions before it. */
class TurnQuestionStorageIT extends TurnApiTestBase {

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @AfterEach
    void removeTheCorpus() {
        jdbc.sql("DELETE FROM knowledge_documents WHERE source_path = 'corpus/turn-test.md'").update();
    }

    private GeneratedQuestion question(String prompt, QuestionOrigin origin, int difficulty, String topic, List<UUID> chunkIds, QuestionDetails details) {
        return new GeneratedQuestion(prompt, List.of(new ExpectedKeyPoint("a", "first point", List.of()), new ExpectedKeyPoint("b", "second point", List.of())),
                details, origin, difficulty, topic, 7, chunkIds,
                origin == QuestionOrigin.GENERATED ? "scripted-question" : null, origin == QuestionOrigin.GENERATED ? "2.1" : null,
                origin == QuestionOrigin.GENERATED ? "scripted-model" : null, null);
    }

    @Test
    void theQuestionIsStoredAsGeneratedWithItsKeyPointsTopicDifficultyAndRubric() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        generator.script(request -> question("Why does volatile not make i++ atomic?", QuestionOrigin.GENERATED, request.targetDifficulty(),
                request.topic().key(), List.of(), null));

        JsonNode response = answerIntroduction(ada, id);

        var row = jdbc.sql("""
                        SELECT prompt, source, difficulty, rubric_id, kind, section, followup_depth, parent_question_id, generation_llm_call_id,
                               expected_key_points::text AS key_points, topic_id, asked_at, details
                        FROM questions WHERE interview_id = :id AND sequence_no = 2""").param("id", id).query().singleRow();
        assertThat(row).containsEntry("prompt", "Why does volatile not make i++ atomic?").containsEntry("source", "GENERATED")
                .containsEntry("rubric_id", "technical.v2").containsEntry("kind", "TECHNICAL").containsEntry("section", "QUESTIONING")
                .containsEntry("followup_depth", 0);
        assertThat(row.get("parent_question_id")).isNull();
        assertThat(row.get("generation_llm_call_id")).isNull();
        assertThat(row.get("details")).isNull();
        assertThat(json.readTree((String) row.get("key_points")).findValuesAsString("id")).containsExactly("a", "b");
        assertThat(jdbc.sql("SELECT s.slug || '/' || t.slug FROM topics t JOIN skills s ON s.id = t.skill_id WHERE t.id = :t")
                .param("t", row.get("topic_id")).query(String.class).single()).isEqualTo(generator.requests().getFirst().topic().key());
        assertThat(((java.sql.Timestamp) row.get("asked_at")).toInstant()).isEqualTo(clock.instant());
        // The candidate sees the prompt and the topic, nothing of the key points.
        JsonNode next = response.get("nextQuestion");
        assertThat(next.get("prompt").asString()).isEqualTo("Why does volatile not make i++ atomic?");
        assertThat(next.get("topic").get("name").asString()).isNotBlank();
        assertThat(next.toString()).doesNotContain("first point").doesNotContain("technical.v2");
    }

    @Test
    void aQuestionAtAnotherDifficultyThanAskedForIsRejectedAndTheTurnWaitsForAUsableOne() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        generator.script(request -> question("An easier one", QuestionOrigin.GENERATED, 1, request.topic().key(), List.of(), null));

        JsonNode response = answerIntroduction(ada, id);

        assertThat(generator.requests().getFirst().targetDifficulty()).isGreaterThan(1);
        assertThat(response.get("awaitingNextQuestion").asBoolean()).isTrue();
        assertThat(count("questions", id)).isEqualTo(1);

        generator.script(request -> question("At the level asked for", QuestionOrigin.GENERATED, request.targetDifficulty(),
                request.topic().key(), List.of(), null));
        postAs(ada, "/interviews/" + id + "/questions/next").andExpect(status().isOk());
        assertThat(questionRows(id).get(1)).containsEntry("difficulty", generator.lastRequest().targetDifficulty());
    }

    @Test
    void anExemplarFallbackIsStoredAsSuch() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        generator.script(request -> question("From the exemplar bank", QuestionOrigin.EXEMPLAR_FALLBACK, request.targetDifficulty(),
                request.topic().key(), List.of(), null));

        answerIntroduction(ada, id);

        assertThat(questionRows(id).get(1)).containsEntry("source", "EXEMPLAR_FALLBACK");
    }

    @Test
    void theChunksAQuestionWasGroundedInAreStoredInTheOrderGiven() throws Exception {
        KnowledgeDocument saved = documents.saveAndFlush(KnowledgeDocument.curated(
                "corpus/turn-test.md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('7')), Map.of(), clock.instant()));
        List<UUID> chunkIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunkIds.add(chunks.saveAndFlush(new KnowledgeChunk(saved.getId(), i, null, "chunk " + i, 10, null, null, Map.of(), clock.instant())).getId());
        }
        List<UUID> shuffled = List.of(chunkIds.get(2), chunkIds.get(0), chunkIds.get(1));
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        generator.script(request -> question("Grounded", QuestionOrigin.GENERATED, request.targetDifficulty(), request.topic().key(), shuffled, null));

        answerIntroduction(ada, id);

        UUID stored = jdbc.sql("SELECT id FROM questions WHERE interview_id = :id AND sequence_no = 2").param("id", id).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT chunk_id FROM question_sources WHERE question_id = :q ORDER BY rank").param("q", stored).query(UUID.class).list())
                .containsExactlyElementsOf(shuffled);
        assertThat(jdbc.sql("SELECT rank FROM question_sources WHERE question_id = :q ORDER BY rank").param("q", stored).query(Integer.class).list())
                .containsExactly(1, 2, 3);
    }

    @Test
    void aCodingProblemFromTheBankKeepsItsMaterialAndTheCandidateSeesItWithoutTheKeyPoints() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        JsonNode current = currentQuestion(ada, id);
        evaluator.quality(com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality.INCORRECT);
        while (!current.get("answerFormat").asString().equals("CODE")) {
            clock.advance(Duration.ofSeconds(10));
            current = answerOk(ada, id, UUID.fromString(current.get("id").asString()), "text").get("nextQuestion");
        }

        UUID questionId = UUID.fromString(current.get("id").asString());
        String problemId = jdbc.sql("SELECT coding_problem_id FROM questions WHERE id = :q").param("q", questionId).query(String.class).single();
        com.techinterviewer.coding.problem.CodingProblem problem = com.techinterviewer.coding.problem.ProblemBank.load().find(problemId).orElseThrow();
        JsonNode details = current.get("details");
        assertThat(details.get("language").asString()).isEqualTo("java");
        assertThat(details.get("title").asString()).isEqualTo(problem.title());
        assertThat(details.get("constraints").get(0).asString()).isEqualTo(problem.constraints().getFirst());
        assertThat(details.get("examples").size()).isEqualTo(problem.examples().size());
        assertThat(details.get("starterCode").asString()).contains("class Solution").contains(problem.signature().name());
        assertThat(current.get("prompt").asString()).isEqualTo(problem.statement());
        assertThat(jdbc.sql("SELECT source FROM questions WHERE id = :q").param("q", questionId).query(String.class).single()).isEqualTo("STATIC");
        assertThat(jdbc.sql("SELECT jsonb_array_length(expected_key_points) FROM questions WHERE id = :q").param("q", questionId)
                .query(Integer.class).single()).isEqualTo(problem.keyPoints().size());
        assertThat(current.toString()).doesNotContain(problem.keyPoints().getFirst().text());
    }

    @Test
    void theGeneratorIsToldAboutTheLastHundredQuestionsAskedAndNoMore() throws Exception {
        UUID ada = newUser();
        var interview = fixture.inState(ada, InterviewState.QUESTIONING, clock.instant());
        List<String> prompts = new ArrayList<>();
        prompts.add(questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId()).getFirst().getPrompt());
        for (int i = 0; i < 100; i++) {
            InterviewQuestion asked = fixture.askTechnical(interview.getId(), "java", "concurrency", clock.instant());
            fixture.answer(asked, "answer " + i, clock.instant());
            prompts.add(asked.getPrompt());
        }
        fixture.setMemory(interview.getId(), new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                PendingStep.nextTopic(Section.QUESTIONING, "java/concurrency", 3)), clock.instant());
        fixture.clearCurrentQuestion(interview.getId(), clock.instant());
        assertThat(prompts).hasSize(101);

        postAs(ada, "/interviews/" + interview.getId() + "/questions/next").andExpect(status().isOk());

        var asked = generator.lastRequest().askedQuestions();
        assertThat(asked).hasSize(100);
        assertThat(asked.getFirst().prompt()).as("the very first question dropped out of the history").isEqualTo(prompts.get(1));
        assertThat(asked.getLast().prompt()).isEqualTo(prompts.get(100));
        assertThat(asked.getLast().topicKey()).isEqualTo("java/concurrency");
        assertThat(count("questions", interview.getId())).isEqualTo(102);
    }

    @Test
    void theGeneratorGetsTheConfigurationOfTheInterviewAndTheSkillsTheCandidateChose() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, with(TECHNICAL_CONFIGURATION, "experienceLevel", "SENIOR"));

        answerIntroduction(ada, id);

        var request = generator.requests().getFirst();
        assertThat(request.interviewId()).isEqualTo(id);
        assertThat(request.role().name()).isEqualTo("BACKEND");
        assertThat(request.experience().name()).isEqualTo("SENIOR");
        assertThat(request.focusSkills()).containsExactly("Java", "Spring Boot");
        assertThat(request.codingLanguage()).isNull();
        assertThat(request.candidateClaims()).isEmpty();
        assertThat(request.askedQuestions()).hasSize(1);
        assertThat(request.askedQuestions().getFirst().topicKey()).as("the introduction has no topic").isNull();
        assertThat(request.section().name()).isEqualTo("QUESTIONING");
        assertThat(request.kind().name()).isEqualTo("TECHNICAL");
    }
}
