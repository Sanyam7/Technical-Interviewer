package com.techinterviewer.interview.turn;

import com.techinterviewer.resume.ScriptedResumeExtractor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator;
import com.techinterviewer.evaluation.support.ScriptedQuestionGenerator;
import com.techinterviewer.evaluation.support.ScriptedReportNarrator;
import com.techinterviewer.interview.InterviewApiTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base of the turn tests: the whole application against a real PostgreSQL with real bearer tokens, a clock the test
 * moves and scripted doubles for the three AI ports, nothing else faked. Every test starts with all three ports
 * available and judging answers as AVERAGE.
 */
@Import(TurnTestPorts.class)
public abstract class TurnApiTestBase extends InterviewApiTestBase {

    @Autowired
    protected ScriptedQuestionGenerator generator;

    @Autowired
    protected ScriptedAnswerEvaluator evaluator;

    @Autowired
    protected ScriptedReportNarrator narrator;

    @Autowired
    protected ScriptedResumeExtractor resumeExtractor;

    @BeforeEach
    void resetTheAiDoubles() {
        generator.reset();
        evaluator.reset();
        narrator.reset();
        resumeExtractor.reset();
        narrator.llmCallId(null);
        evaluator.quality(ScriptedAnswerEvaluator.Quality.AVERAGE);
    }

    @AfterEach
    void noPortWasCalledInsideATransaction() {
        assertThat(generator.transactionViolations()).as("the question generator was called inside a transaction").isEmpty();
        assertThat(evaluator.transactionViolations()).as("the answer evaluator was called inside a transaction").isEmpty();
        assertThat(narrator.transactionViolations()).as("the report narrator was called inside a transaction").isEmpty();
    }

    // ---- requests -----------------------------------------------------------------------------------------------

    protected static String answerBody(UUID questionId, String content) {
        return "{\"questionId\":\"" + questionId + "\",\"content\":" + quote(content) + "}";
    }

    protected static String quote(String text) {
        return tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(text).toString();
    }

    protected ResultActions answer(UUID user, UUID interview, UUID question, String content) throws Exception {
        return postAs(user, "/interviews/" + interview + "/answers", answerBody(question, content));
    }

    /** Answers the question and expects 200; returns the answer response. */
    protected JsonNode answerOk(UUID user, UUID interview, UUID question, String content) throws Exception {
        return body(answer(user, interview, question, content).andExpect(status().isOk()));
    }

    protected JsonNode skipOk(UUID user, UUID interview, UUID question) throws Exception {
        return body(postAs(user, "/interviews/" + interview + "/questions/" + question + "/skip").andExpect(status().isOk()));
    }

    protected ResultActions finish(UUID user, UUID interview, String reason) throws Exception {
        return postAs(user, "/interviews/" + interview + "/finish", "{\"reason\":\"" + reason + "\"}");
    }

    protected JsonNode session(UUID user, UUID interview) throws Exception {
        return body(getAs(user, "/interviews/" + interview).andExpect(status().isOk()));
    }

    protected UUID currentQuestionId(UUID user, UUID interview) throws Exception {
        JsonNode current = session(user, interview).get("currentQuestion");
        assertThat(current.isNull()).as("the interview has an open question").isFalse();
        return UUID.fromString(current.get("id").asString());
    }

    /** The question the session offers, as the candidate sees it. */
    protected JsonNode currentQuestion(UUID user, UUID interview) throws Exception {
        return session(user, interview).get("currentQuestion");
    }

    protected static String text(JsonNode node, String property) {
        return node.get(property).asString();
    }

    // ---- driving an interview ------------------------------------------------------------------------------------

    /** A reasonable answer for a question: code for a CODE question, prose otherwise. */
    protected static String answerFor(JsonNode question) {
        return "An answer to: " + question.get("prompt").asString();
    }

    /** What one answered question looked like to the candidate. */
    public record Turn(JsonNode question, JsonNode response) {
    }

    /**
     * Answers questions until the interview is no longer asking, advancing the clock by {@code pause} between answers.
     * {@code choose} gets the question and returns the next action: a string answers, null skips.
     */
    protected List<Turn> play(UUID user, UUID interview, Duration pause, Function<JsonNode, String> choose) throws Exception {
        List<Turn> turns = new ArrayList<>();
        JsonNode current = currentQuestion(user, interview);
        int guard = 0;
        while (current != null && !current.isNull()) {
            assertThat(++guard).as("answers given").isLessThan(200);
            clock.advance(pause);
            String content = choose.apply(current);
            UUID questionId = UUID.fromString(current.get("id").asString());
            JsonNode response;
            if (content == null) {
                response = skipOk(user, interview, questionId);
            } else if ("CODE".equals(current.get("answerFormat").asString())) {
                ObjectNode node = json.createObjectNode();
                node.put("questionId", questionId.toString());
                node.put("content", content);
                node.put("codeLanguage", "java");
                response = body(postAs(user, "/interviews/" + interview + "/answers", node.toString()).andExpect(status().isOk()));
            } else {
                response = answerOk(user, interview, questionId, content);
            }
            turns.add(new Turn(current, response));
            current = response.get("nextQuestion");
        }
        return turns;
    }

    protected List<Turn> playAll(UUID user, UUID interview) throws Exception {
        return play(user, interview, Duration.ofSeconds(40), TurnApiTestBase::answerFor);
    }

    // ---- what the database says ---------------------------------------------------------------------------------

    /** The transition log as {@code from>to:event}, in order. */
    protected List<String> transitionLog(UUID interview) {
        return jdbc.sql("SELECT coalesce(from_state, '-') || '>' || to_state || ':' || event FROM interview_state_transitions "
                        + "WHERE interview_id = :id ORDER BY id")
                .param("id", interview)
                .query(String.class)
                .list();
    }

    protected String stateOf(UUID interview) {
        return jdbc.sql("SELECT state FROM interviews WHERE id = :id").param("id", interview).query(String.class).single();
    }

    protected long count(String table, UUID interview) {
        String column = table.equals("evaluations") ? "answer_id IN (SELECT id FROM answers WHERE interview_id = :id)" : "interview_id = :id";
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column).param("id", interview).query(Long.class).single();
    }

    /** One row per question in the order asked: {@code sequence_no, kind, section, topic key or -, difficulty, depth, source}. */
    protected List<Map<String, Object>> questionRows(UUID interview) {
        return jdbc.sql("""
                        SELECT q.sequence_no, q.kind, q.section, coalesce(s.slug || '/' || t.slug, '-') AS topic, q.difficulty,
                               q.followup_depth AS depth, q.source, q.parent_question_id, q.id, q.rubric_id
                        FROM questions q LEFT JOIN topics t ON t.id = q.topic_id LEFT JOIN skills s ON s.id = t.skill_id
                        WHERE q.interview_id = :id ORDER BY q.sequence_no""")
                .param("id", interview)
                .query().listOfRows();
    }

    /** The stored plan of the interview as JSON: sections with minutes, slots and topic quotas. */
    protected JsonNode planOf(UUID interview) {
        return json.readTree(jdbc.sql("SELECT plan::text FROM interviews WHERE id = :id").param("id", interview).query(String.class).single());
    }

    protected int slotsOf(UUID interview, String section) {
        for (JsonNode planned : planOf(interview).get("sections")) {
            if (planned.get("section").asString().equals(section)) {
                return planned.get("slots").asInt();
            }
        }
        throw new IllegalArgumentException("no section " + section);
    }

    /** The same configuration with another duration. */
    protected String technical(int minutes) {
        return with(TECHNICAL_CONFIGURATION, "durationMinutes", minutes);
    }

    protected UUID startedInterview(UUID user, String configuration) throws Exception {
        UUID id = createInterview(user, configuration);
        postAs(user, "/interviews/" + id + "/start").andExpect(status().isOk());
        return id;
    }

    /** Answers whatever question is current, advancing the clock a little first; returns the answer response. */
    protected JsonNode answerCurrent(UUID user, UUID interview, String content) throws Exception {
        clock.advance(Duration.ofSeconds(10));
        return answerOk(user, interview, currentQuestionId(user, interview), content);
    }

    /** Answers the introduction, so the interview is in its first question section. */
    protected JsonNode answerIntroduction(UUID user, UUID interview) throws Exception {
        return answerCurrent(user, interview, "I have been building backend systems for six years.");
    }
}
