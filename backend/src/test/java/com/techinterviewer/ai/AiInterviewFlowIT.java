package com.techinterviewer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.ai.observability.LlmCall;
import com.techinterviewer.ai.observability.LlmCallRepository;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.ReportNarrator;
import com.techinterviewer.ai.agents.AgentQuestionGenerator;
import com.techinterviewer.ai.agents.AnswerEvaluatorAgent;
import com.techinterviewer.ai.agents.ReportNarratorAgent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A whole interview through the HTTP API with the real AI layer: the agents, the structured-output client and the real
 * Ollama provider over HTTP, against a test double of a model that answers from each request's schema. Proves the layer
 * is wired into the interview (questions, follow-ups, evaluations, report narrative), that every result links to its
 * {@code llm_calls} row, that the tasks are grounded in the knowledge base (question sources, cited chunks, further
 * reading), and that the scores still come from the server.
 */
class AiInterviewFlowIT extends AiIntegrationTestBase {

    /** Five different answers: the same text twice would be a repeated answer, which is never followed up. */
    private static final List<String> ANSWERS = List.of(
            "I have built payment services in Java for five years and led the move to event-driven processing.",
            "I would use a bounded queue with back-pressure and measure the latency under load.",
            "Each consumer commits its offset only after the write succeeded, so a crash replays the message instead of losing it.",
            "A covering index on the customer and the creation date lets the planner answer the report without touching the table.",
            "The hook keeps the previous value in a ref and compares it in an effect, which avoids a render loop.");

    @Autowired
    LlmCallRepository calls;

    @Autowired
    QuestionGenerator questionGenerator;

    @Autowired
    AnswerEvaluator answerEvaluator;

    @Autowired
    ReportNarrator reportNarrator;

    @Test
    void theAgentsServeThePorts() {
        assertThat(questionGenerator).isInstanceOf(AgentQuestionGenerator.class);
        assertThat(answerEvaluator).isInstanceOf(AnswerEvaluatorAgent.class);
        assertThat(reportNarrator).isInstanceOf(ReportNarratorAgent.class);
    }

    @Test
    void aWholeInterviewRunsOnTheAiLayerAndEveryResultLinksToItsRecordedCall() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);

        for (int turn = 0; turn < 5; turn++) {
            JsonNode question = body(getAs(ada, "/interviews/" + id).andExpect(status().isOk())).get("currentQuestion");
            assertThat(question.isNull()).as("turn " + turn + " has a question").isFalse();
            String answer = ANSWERS.get(turn);
            JsonNode response = body(postAs(ada, "/interviews/" + id + "/answers",
                    "{\"questionId\":\"" + question.get("id").asString() + "\",\"content\":\"" + answer + "\"}").andExpect(status().isOk()));
            assertThat(response.get("awaitingNextQuestion").asBoolean()).as("turn " + turn + " got its next question").isFalse();
        }
        JsonNode finished = body(postAs(ada, "/interviews/" + id + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}").andExpect(status().isOk()));
        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");

        Map<UUID, LlmCall> recorded = calls.findByInterviewIdOrderByCreatedAtAsc(id).stream()
                .collect(Collectors.toMap(LlmCall::getId, call -> call));
        Map<UUID, LlmCall> generations = recorded.entrySet().stream().filter(entry -> entry.getValue().getPromptId() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(generations.values()).allSatisfy(call -> {
            assertThat(call.getStatus()).isEqualTo(LlmCallStatus.OK);
            assertThat(call.getProvider()).isEqualTo("ollama");
            assertThat(call.getModel()).isEqualTo("fake-model");
            assertThat(call.getPromptVersion()).isEqualTo(
                    java.util.Set.of("answer-evaluation-intro", "fundamentals-question", "scenario-question", "experience-question")
                            .contains(call.getPromptId()) ? "v1"
                    : call.getPromptId().startsWith("answer-evaluation-") ? "v3" : "v2");
            assertThat(call.getInputTokens()).isEqualTo(400);
            if (call.getPromptId().equals("answer-evaluation-intro")) {
                assertThat(call.getRagChunkIds()).as("the introduction is not grounded").isNull();
            } else {
                // Retrieval ran for every other task. Whether it found material depends on the test model's hashing
                // embedding, which knows words, not meaning: RetrievalQualityIT and HybridRetrieverIT measure that part.
                assertThat(call.getRagChunkIds()).as(call.getPromptId() + " recorded its retrieval").isNotNull();
            }
        });
        // Every grounded task embedded its search query once, and every generated question was embedded for de-duplication;
        // those calls are recorded too.
        assertThat(recorded.values()).filteredOn(call -> call.getPromptId() == null).isNotEmpty().allSatisfy(call -> {
            assertThat(call.getTask()).isIn("QUERY_EMBEDDING", "SIMILARITY_EMBEDDING");
            assertThat(call.getModel()).isEqualTo(EMBEDDING_MODEL);
            assertThat(call.getStatus()).isEqualTo(LlmCallStatus.OK);
        });

        // Every generated question, follow-ups included, links to the call that wrote it.
        List<Map<String, Object>> questions = jdbc.sql("""
                SELECT kind, source, generation_llm_call_id, difficulty, embedding_model, embedding IS NOT NULL AS embedded
                FROM questions WHERE interview_id = ? ORDER BY sequence_no""")
                .param(id).query().listOfRows();
        assertThat(questions.getFirst()).containsEntry("kind", "INTRO").containsEntry("source", "STATIC");
        assertThat(questions.subList(1, questions.size())).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get("source")).isEqualTo("GENERATED");
            LlmCall call = recorded.get((UUID) row.get("generation_llm_call_id"));
            assertThat(call).isNotNull();
            assertThat(call.getTask()).isEqualTo(row.get("kind").equals("FOLLOW_UP") ? "FOLLOW_UP_GENERATION" : "QUESTION_GENERATION");
            assertThat(call.getPromptId()).isIn("technical-question", "fundamentals-question", "scenario-question", "experience-question", "follow-up-question");
            // Stored with its vector, so later questions of the interview are compared with it.
            assertThat(row.get("embedded")).isEqualTo(true);
            assertThat(row.get("embedding_model")).isEqualTo(EMBEDDING_MODEL);
        });
        assertThat(questions.getFirst().get("embedded")).as("the static introduction is never generated").isEqualTo(false);
        long similarityCalls = recorded.values().stream().filter(call -> call.getTask().equals("SIMILARITY_EMBEDDING")).count();
        assertThat(similarityCalls).as("one per generated question (more when a draft was rejected)")
                .isGreaterThanOrEqualTo(questions.size() - 1);
        // Each generated question is grounded: its sources are the chunks its prompt showed, in rank order.
        List<Map<String, Object>> sources = jdbc.sql("""
                SELECT q.generation_llm_call_id, s.chunk_id, s.rank FROM question_sources s JOIN questions q ON q.id = s.question_id
                WHERE q.interview_id = ? ORDER BY q.sequence_no, s.rank""").param(id).query().listOfRows();
        assertThat(sources).allSatisfy(row -> assertThat(recorded.get((UUID) row.get("generation_llm_call_id"))
                .getRagChunkIds()).contains((UUID) row.get("chunk_id")));
        assertThat(generations.values()).filteredOn(call -> call.getTask().endsWith("_GENERATION")).allSatisfy(call -> assertThat(
                sources.stream().filter(row -> call.getId().equals(row.get("generation_llm_call_id"))).map(row -> (UUID) row.get("chunk_id")).toList())
                .as("a question's sources are exactly the chunks its prompt showed").isEqualTo(call.getRagChunkIds()));
        assertThat(questions).as("a partly covered answer was followed up").anySatisfy(row -> assertThat(row.get("kind")).isEqualTo("FOLLOW_UP"));

        // Every evaluation links to its call and has verified evidence; its score is the server's arithmetic.
        List<Map<String, Object>> evaluations = jdbc.sql("""
                SELECT e.llm_call_id, e.prompt_id, e.overall_score, e.key_points::text AS key_points, e.criterion_scores::text AS criteria
                FROM evaluations e JOIN answers a ON a.id = e.answer_id WHERE a.interview_id = ?""").param(id).query().listOfRows();
        assertThat(evaluations).hasSize(5).allSatisfy(row -> {
            LlmCall call = recorded.get((UUID) row.get("llm_call_id"));
            assertThat(call.getTask()).isEqualTo("ANSWER_EVALUATION");
            assertThat(call.getPromptId()).isEqualTo(row.get("prompt_id"));
            // The fake model rates every criterion 3 (75). Where it also judged one of three key points missing and one partly
            // covered, the server caps completeness at ceil(4 * 1.5 / 3) = 2: 0.10 * 1 / 4 * 100 = 2.5 points less.
            boolean missing = ((String) row.get("key_points")).contains("\"MISSING\"");
            assertThat(((java.math.BigDecimal) row.get("overall_score")).doubleValue()).isEqualTo(missing ? 72.5 : 75.0);
            assertThat(((String) row.get("criteria")).contains("KEY_POINT_COVERAGE")).isEqualTo(missing);
        });
        assertThat(evaluations).filteredOn(row -> !row.get("prompt_id").equals("answer-evaluation-intro"))
                .allSatisfy(row -> assertThat((String) row.get("key_points")).contains("\"verified\": true"));

        // The report: deterministic scores, a narrative from the model citing this interview's evaluations.
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("outcome").asString()).isEqualTo("COMPLETE");
        assertThat(report.get("overallScore").asDouble()).as("the difficulty-weighted mean of turns scored 75 and 72.5")
                .isBetween(72.5, 75.0);
        assertThat(report.get("summary").asString()).isEqualTo("The candidate answered the question clearly.");
        assertThat(report.get("strengths")).isNotEmpty();
        UUID reportCall = jdbc.sql("SELECT llm_call_id FROM reports WHERE interview_id = ?").param(id).query(UUID.class).single();
        assertThat(recorded.get(reportCall).getTask()).isEqualTo("REPORT_WRITING");
        List<UUID> reportMaterial = recorded.get(reportCall).getRagChunkIds();
        assertThat(report.get("furtherReading").size()).as("further reading only from the material the narrator was shown")
                .isLessThanOrEqualTo(reportMaterial.size());
        report.get("furtherReading").forEach(reading -> assertThat(reading.get("source").asString()).endsWith(".md"));

        Set<String> tasks = recorded.values().stream().map(LlmCall::getTask).collect(Collectors.toSet());
        assertThat(tasks).containsExactlyInAnyOrder(
                "QUESTION_GENERATION", "FOLLOW_UP_GENERATION", "ANSWER_EVALUATION", "REPORT_WRITING", "QUERY_EMBEDDING", "SIMILARITY_EMBEDDING");
        assertThat(MODEL.received()).allSatisfy(request -> assertThat(request.path()).isIn("/api/chat", "/api/embed"));
    }
}
