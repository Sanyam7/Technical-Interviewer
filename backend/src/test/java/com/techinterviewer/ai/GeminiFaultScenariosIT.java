package com.techinterviewer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.support.FakeModelServer;
import com.techinterviewer.ai.support.FakeModelServer.Fault;
import com.techinterviewer.interview.InterviewApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * What a candidate experiences when Gemini misbehaves, through the whole application: the HTTP API, the real Gemini provider
 * speaking the real wire protocol, the structured-output client, the agents, RAG and PostgreSQL. Only the model is a double
 * ({@link FakeModelServer}), and it fails the way Gemini does: a call that outlives its timeout, a 429 with Retry-After,
 * output that is not the JSON the schema asks for, a 500. What must hold:
 * <ul>
 *   <li>a fault that clears within the retry policy is invisible: the turn answers normally, and the call log shows the fault
 *       and the retry;
 *   <li>a fault that persists loses nothing: the answer is accepted and stored, its evaluation waits (no score is invented), the
 *       interview waits for its next question, and once Gemini is back the question is produced and the report evaluates every
 *       answer;
 *   <li>malformed output is never stored: it is sent back once for repair, and then treated as an outage;
 *   <li>without reference material (the embedding service down, nothing retrieved) the interview runs ungrounded and cites
 *       nothing.
 * </ul>
 */
class GeminiFaultScenariosIT extends InterviewApiTestBase {

    private static final FakeModelServer GEMINI = new FakeModelServer(TestJson.applicationMapper());

    @DynamicPropertySource
    static void geminiOnly(DynamicPropertyRegistry registry) {
        registry.add("app.ai.enabled", () -> "true");
        registry.add("app.ai.providers.gemini.api-key", () -> "test-gemini-key");
        registry.add("app.ai.providers.gemini.base-url", () -> GEMINI.baseUrl() + "/v1beta");
        // Shorter than FakeModelServer.TIMEOUT_DELAY, so a TIMEOUT fault times out; one retry, as in production (two there).
        registry.add("app.ai.providers.gemini.timeout", () -> "1s");
        registry.add("app.ai.providers.gemini.max-retries", () -> "1");
        for (String task : List.of("question-generation", "follow-up-generation", "answer-evaluation", "report-writing", "resume-extraction")) {
            registry.add("app.ai.routes." + task + ".provider", () -> "gemini");
            registry.add("app.ai.routes." + task + ".model", () -> "fake-gemini");
        }
        // The fake embeds through the Ollama protocol; embeddings are not what these scenarios break (except the last one).
        registry.add("app.ai.providers.ollama.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.base-url", () -> GEMINI.baseUrl().toString());
        registry.add("app.rag.embedding.provider", () -> "ollama");
        registry.add("app.rag.embedding.model", () -> "test-embedding");
        registry.add("app.rag.retrieval.min-similarity", () -> "0.2");
    }

    @AfterEach
    void geminiIsBack() {
        GEMINI.recover();
        GEMINI.failEmbeddings(false);
    }

    private JsonNode session(UUID user, UUID interview) throws Exception {
        return body(getAs(user, "/interviews/" + interview).andExpect(status().isOk()));
    }

    private JsonNode answerCurrent(UUID user, UUID interview, String text) throws Exception {
        JsonNode question = session(user, interview).get("currentQuestion");
        assertThat(question.isNull()).as("there is a question to answer").isFalse();
        return body(postAs(user, "/interviews/" + interview + "/answers",
                json.createObjectNode().put("questionId", question.get("id").asString()).put("content", text).toString())
                .andExpect(status().isOk()));
    }

    private List<String> callStatuses(UUID interview, String task) {
        return jdbc.sql("SELECT status FROM llm_calls WHERE interview_id = ? AND task = ? ORDER BY created_at, attempt")
                .param(interview).param(task).query(String.class).list();
    }

    private List<String> evaluationStatuses(UUID interview) {
        return jdbc.sql("SELECT evaluation_status FROM answers WHERE interview_id = ? AND NOT skipped ORDER BY submitted_at")
                .param(interview).query(String.class).list();
    }

    private static final String INTRODUCTION = "I have built payment services in Java and Spring Boot for five years.";
    private static final String TECHNICAL = "A volatile field makes writes visible to other threads, but count++ is still a read, an add and a "
            + "write, so two threads can lose an update; an AtomicInteger or a lock makes the increment atomic.";

    // ---- a fault that clears ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fault.class, names = {"TIMEOUT", "RATE_LIMITED", "SERVER_ERROR"})
    void aFaultThatClearsWithinTheRetryPolicyIsInvisibleToTheCandidate(Fault fault) throws Exception {
        UUID ada = newUser();
        UUID interview = startedInterview(ada);
        answerCurrent(ada, interview, INTRODUCTION);

        GEMINI.fail(fault, 1);
        JsonNode turn = answerCurrent(ada, interview, TECHNICAL);

        assertThat(turn.get("accepted").asBoolean()).isTrue();
        assertThat(turn.get("awaitingNextQuestion").asBoolean()).as("the next question was produced").isFalse();
        assertThat(turn.get("nextQuestion").isNull()).isFalse();
        assertThat(evaluationStatuses(interview)).as("every answer evaluated").containsOnly("COMPLETED");
        // The call log tells what happened: the failed call, then the retry that worked.
        String recorded = switch (fault) {
            case TIMEOUT -> "TIMEOUT";
            case RATE_LIMITED -> "RATE_LIMITED";
            default -> "ERROR";
        };
        assertThat(callStatuses(interview, "ANSWER_EVALUATION")).containsSubsequence(recorded, "OK");
        assertThat(jdbc.sql("SELECT DISTINCT provider FROM llm_calls WHERE interview_id = ? AND task = 'ANSWER_EVALUATION'")
                .param(interview).query(String.class).list()).containsExactly("gemini");
    }

    @Test
    void malformedOutputIsSentBackOnceAndTheRepairedAnswerIsUsed() throws Exception {
        UUID ada = newUser();
        UUID interview = startedInterview(ada);
        answerCurrent(ada, interview, INTRODUCTION);

        GEMINI.fail(Fault.MALFORMED, 1);
        JsonNode turn = answerCurrent(ada, interview, TECHNICAL);

        assertThat(turn.get("nextQuestion").isNull()).isFalse();
        assertThat(evaluationStatuses(interview)).containsOnly("COMPLETED");
        assertThat(callStatuses(interview, "ANSWER_EVALUATION")).containsSubsequence("INVALID_OUTPUT", "REPAIRED");
        // The rejected text went back to the model with the reason, in the repair request.
        assertThat(GEMINI.received()).anyMatch(request -> request.body().contains("Your previous answer was rejected"));
    }

    // ---- a fault that persists -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fault.class)
    void anOutageLosesNothingAndTheInterviewCatchesUpWhenGeminiIsBack(Fault fault) throws Exception {
        UUID ada = newUser();
        UUID interview = startedInterview(ada);
        answerCurrent(ada, interview, INTRODUCTION);
        int evaluationsBefore = jdbc.sql("SELECT count(*) FROM evaluations e JOIN answers a ON a.id = e.answer_id WHERE a.interview_id = ?")
                .param(interview).query(Integer.class).single();

        GEMINI.failAlways(fault);
        JsonNode turn = answerCurrent(ada, interview, TECHNICAL);

        // The answer is kept; nothing is scored without an evaluation, and the interview waits for its next question.
        assertThat(turn.get("accepted").asBoolean()).isTrue();
        assertThat(turn.get("awaitingNextQuestion").asBoolean()).isTrue();
        assertThat(turn.get("nextQuestion").isNull()).isTrue();
        assertThat(evaluationStatuses(interview)).containsExactly("COMPLETED", "PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM evaluations e JOIN answers a ON a.id = e.answer_id WHERE a.interview_id = ?")
                .param(interview).query(Integer.class).single()).isEqualTo(evaluationsBefore);
        // Asking for the question while Gemini is still down: 503, retryable, and nothing changed.
        long version = session(ada, interview).get("version").asLong();
        postAs(ada, "/interviews/" + interview + "/questions/next").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        assertThat(session(ada, interview).get("version").asLong()).isEqualTo(version);
        // Malformed output was never stored as a question.
        assertThat(jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = ? AND prompt LIKE '%Sure! Here%'").param(interview)
                .query(Integer.class).single()).isZero();

        GEMINI.recover();
        // The waiting answer's next attempt is due after its backoff (at most app.evaluation.backoff-max, 10 s): let it pass.
        clock.advance(java.time.Duration.ofSeconds(15));
        JsonNode recovered = body(postAs(ada, "/interviews/" + interview + "/questions/next").andExpect(status().isOk()));
        assertThat(recovered.get("currentQuestion").isNull()).isFalse();
        postAs(ada, "/interviews/" + interview + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}").andExpect(status().isOk());

        // The answer that waited was evaluated at the finish, and the report is complete.
        assertThat(evaluationStatuses(interview)).containsOnly("COMPLETED");
        JsonNode report = body(getAs(ada, "/interviews/" + interview + "/report").andExpect(status().isOk()));
        assertThat(report.get("status").asString()).isEqualTo("READY");
    }

    @Test
    void aReportWhoseNarrativeCannotBeWrittenIsStillCompleteWithTheComputedScores() throws Exception {
        UUID ada = newUser();
        UUID interview = startedInterview(ada);
        answerCurrent(ada, interview, INTRODUCTION);
        // Enough answers for a confident score: a recommendation (and so a narrative) is withheld on a handful of answers.
        for (int i = 0; i < 6 && !session(ada, interview).get("currentQuestion").isNull(); i++) {
            answerCurrent(ada, interview, TECHNICAL + " Answer " + i + " adds a different example about thread pools number " + i + ".");
        }

        GEMINI.failAlways(Fault.RATE_LIMITED);
        postAs(ada, "/interviews/" + interview + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}").andExpect(status().isOk());

        // The scores are computed by the server, not written by the model: the report is READY without the narrative.
        JsonNode report = body(getAs(ada, "/interviews/" + interview + "/report").andExpect(status().isOk()));
        assertThat(report.get("status").asString()).isEqualTo("READY");
        assertThat(report.get("overallScore").isNumber()).isTrue();
        assertThat(report.get("recommendation").isNull()).as("enough evidence for a recommendation").isFalse();
        assertThat(report.path("summary").isNull() || report.path("summary").isMissingNode()).as("no narrative was invented").isTrue();
        assertThat(callStatuses(interview, "REPORT_WRITING")).isNotEmpty().containsOnly("RATE_LIMITED");
    }

    // ---- no reference material ---------------------------------------------------------------------------------------

    @Test
    void withoutEmbeddingsQuestionsAreGroundedByKeywordsAloneAndTheInterviewRuns() throws Exception {
        GEMINI.failEmbeddings(true);
        UUID ada = newUser();
        UUID interview = startedInterview(ada);
        answerCurrent(ada, interview, INTRODUCTION);
        JsonNode turn = answerCurrent(ada, interview, TECHNICAL);
        answerCurrent(ada, interview, TECHNICAL.replace("volatile", "synchronized"));

        assertThat(turn.get("nextQuestion").isNull()).isFalse();
        assertThat(evaluationStatuses(interview)).containsOnly("COMPLETED");
        // The vector half of the search is down; the keyword half still finds the passages that name the identifiers of a question's
        // curated angle (HashMap, @Transactional, ...), within the scope of the question's skill (AI_ARCHITECTURE.md section 2.15).
        List<String> technologies = jdbc.sql("""
                        SELECT DISTINCT d.metadata ->> 'technology' FROM question_sources s
                        JOIN questions q ON q.id = s.question_id JOIN knowledge_chunks c ON c.id = s.chunk_id
                        JOIN knowledge_documents d ON d.id = c.document_id WHERE q.interview_id = ?""")
                .param(interview).query(String.class).list();
        assertThat(technologies).isSubsetOf("java", "spring-boot", "rest");
        postAs(ada, "/interviews/" + interview + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}").andExpect(status().isOk());
        JsonNode report = body(getAs(ada, "/interviews/" + interview + "/report").andExpect(status().isOk()));
        assertThat(report.get("status").asString()).isEqualTo("READY");
    }
}
