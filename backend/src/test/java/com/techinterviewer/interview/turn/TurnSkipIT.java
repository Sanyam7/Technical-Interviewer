package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Skipping: a skipped main question scores 0, a skipped follow-up adds nothing, and nobody evaluates what was not said. */
class TurnSkipIT extends TurnApiTestBase {

    private JsonNode memory(UUID interview) {
        return json.readTree(jdbc.sql("SELECT memory::text FROM interviews WHERE id = :id").param("id", interview).query(String.class).single());
    }

    @Test
    void aSkippedMainQuestionIsRecordedAsSkippedScoresZeroInTheMemoryAndTheInterviewMovesOn() throws Exception {
        evaluator.quality(Quality.EXCELLENT);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        JsonNode root = currentQuestion(ada, id);
        UUID question = UUID.fromString(root.get("id").asString());
        int evaluatorCalls = evaluator.calls();

        JsonNode response = skipOk(ada, id, question);

        assertThat(evaluator.calls()).as("a skip is never evaluated").isEqualTo(evaluatorCalls);
        assertThat(response.get("accepted").asBoolean()).isTrue();
        assertThat(response.get("state").asString()).as("nothing to follow up on").isEqualTo("QUESTIONING");
        assertThat(response.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(response.get("progress").get("questionsAnswered").asInt()).as("a skipped main question uses its slot").isEqualTo(1);
        var row = jdbc.sql("SELECT content, skipped, evaluation_status FROM answers WHERE question_id = :q").param("q", question).query().singleRow();
        assertThat(row).containsEntry("content", "").containsEntry("skipped", true).containsEntry("evaluation_status", "COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM evaluations e JOIN answers a ON a.id = e.answer_id WHERE a.question_id = :q").param("q", question)
                .query(Long.class).single()).isZero();
        JsonNode memory = memory(id);
        assertThat(memory.get("answerSummaries").get(1).get("questionId").asString()).isEqualTo(question.toString());
        assertThat(memory.get("answerSummaries").get(1).get("score").asDouble()).isZero();
        String topic = request(0).topic().key();
        assertThat(memory.get("topicStats").get(topic).get("lastScore").asDouble()).isZero();
        assertThat(memory.get("topicStats").get(topic).get("asked").asInt()).isEqualTo(1);
        assertThat(transitionLog(id).getLast()).isEqualTo("QUESTIONING>QUESTIONING:ANSWER_EVALUATED");
    }

    private com.techinterviewer.interview.port.QuestionRequest request(int index) {
        return generator.requests().get(index);
    }

    @Test
    void aSkippedFollowUpAddsNothingAndEndsTheThread() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "a partial answer");
        assertThat(stateOf(id)).isEqualTo("FOLLOW_UP");
        String topic = request(0).topic().key();
        double rootScore = memory(id).get("topicStats").get(topic).get("lastScore").asDouble();
        int summaries = memory(id).get("answerSummaries").size();
        UUID followUp = currentQuestionId(ada, id);

        JsonNode response = skipOk(ada, id, followUp);

        assertThat(response.get("state").asString()).isEqualTo("QUESTIONING");
        assertThat(response.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(transitionLog(id).getLast()).isEqualTo("FOLLOW_UP>QUESTIONING:FOLLOW_UP_DONE");
        JsonNode memory = memory(id);
        assertThat(memory.get("answerSummaries")).as("no summary for the skipped follow-up").hasSize(summaries);
        assertThat(memory.get("topicStats").get(topic).get("lastScore").asDouble()).isEqualTo(rootScore);
        assertThat(memory.get("topicStats").get(topic).get("asked").asInt()).as("only main questions use quota").isEqualTo(1);
        assertThat(response.get("progress").get("questionsAnswered").asInt()).isEqualTo(1);
    }

    @Test
    void aSkippedIntroductionStillOpensTheFirstSection() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);

        JsonNode response = skipOk(ada, id, currentQuestionId(ada, id));

        assertThat(response.get("state").asString()).isEqualTo("QUESTIONING");
        assertThat(response.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(evaluator.calls()).isZero();
        assertThat(transitionLog(id).getLast()).isEqualTo("INTRODUCTION>QUESTIONING:INTRO_ANSWERED");
    }

    @Test
    void aSkippedMainQuestionLowersTheReportAndIsCountedAsSkipped() throws Exception {
        evaluator.quality(Quality.EXCELLENT);
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(30));
        answerIntroduction(ada, id);
        // First root: answered excellently (a follow-up may follow); then skip every question until the interview ends once.
        evaluator.quality(Quality.INCORRECT);
        int skipped = 0;
        JsonNode current = currentQuestion(ada, id);
        while (current != null && !current.isNull()) {
            clock.advance(Duration.ofSeconds(10));
            if (skipped < 1) {
                current = skipOk(ada, id, UUID.fromString(current.get("id").asString())).get("nextQuestion");
                skipped++;
            } else {
                current = answerOk(ada, id, UUID.fromString(current.get("id").asString()), "no idea").get("nextQuestion");
            }
        }

        body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));

        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("coverage").get("skipped").asInt()).isEqualTo(1);
        assertThat(report.get("overallScore").asDouble()).isLessThan(20);
    }

    @Test
    void anInterviewInWhichEverythingWasSkippedHasNoScoreButAReport() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(15));
        JsonNode current = currentQuestion(ada, id);
        while (current != null && !current.isNull()) {
            current = skipOk(ada, id, UUID.fromString(current.get("id").asString())).get("nextQuestion");
        }

        JsonNode finished = body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));

        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        assertThat(evaluator.calls()).isZero();
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("overallScore").isNull()).isTrue();
        assertThat(report.get("coverage").get("skipped").asInt()).isEqualTo((int) count("answers", id));
    }
}
