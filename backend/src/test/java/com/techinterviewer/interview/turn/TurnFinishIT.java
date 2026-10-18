package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Finishing: the candidate ends early or asks for the report, and the final evaluation runs inside the request. */
class TurnFinishIT extends TurnApiTestBase {

    private String endReason(UUID interview) {
        return jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", interview).query(String.class).single();
    }

    @Test
    void aCandidateWhoEndsTheInterviewBeforeAnsweringAnythingGetsAReportWithoutAScore() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);

        JsonNode finished = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));

        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        assertThat(finished.get("retryable").asBoolean()).isFalse();
        assertThat(finished.get("reportUrl").asString()).isEqualTo("/api/v1/interviews/" + id + "/report");
        assertThat(transitionLog(id)).containsExactly(
                "->INIT:CREATE", "INIT>INTRODUCTION:START", "INTRODUCTION>FINAL_EVALUATION:END_REQUESTED", "FINAL_EVALUATION>COMPLETED:REPORT_READY");
        assertThat(endReason(id)).isEqualTo("CANDIDATE_ENDED");
        assertThat(jdbc.sql("SELECT current_question_id IS NULL FROM interviews WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(evaluator.calls()).isZero();
        assertThat(generator.calls()).isZero();
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("status").asString()).isEqualTo("READY");
        assertThat(report.get("overallScore").isNull()).as("no evidence, no score").isTrue();
        assertThat(report.get("recommendation").isNull()).isTrue();
    }

    @Test
    void aCandidateWhoEndsTheInterviewAfterSomeAnswersGetsAReportOfWhatWasAnswered() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "first");
        answerCurrent(ada, id, "second");
        UUID unanswered = currentQuestionId(ada, id);
        int evaluatorCalls = evaluator.calls();

        JsonNode finished = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));

        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(evaluator.calls()).as("everything was evaluated already").isEqualTo(evaluatorCalls);
        assertThat(transitionLog(id)).contains("FOLLOW_UP>FINAL_EVALUATION:END_REQUESTED".replace("FOLLOW_UP", stateBeforeEnd(id)));
        assertThat(endReason(id)).isEqualTo("CANDIDATE_ENDED");
        assertThat(jdbc.sql("SELECT count(*) FROM answers WHERE question_id = :q").param("q", unanswered).query(Long.class).single()).isZero();
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("overallScore").isNull()).isFalse();
        assertThat(report.get("coverage").get("questionsAnswered").asInt()).isEqualTo((int) count("answers", id));
    }

    /** The state the interview was in when it was ended: the from state of the END_REQUESTED row. */
    private String stateBeforeEnd(UUID interview) {
        return jdbc.sql("SELECT from_state FROM interview_state_transitions WHERE interview_id = :id AND event = 'END_REQUESTED'")
                .param("id", interview).query(String.class).single();
    }

    @Test
    void endingDuringAFollowUpLeavesNothingPending() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "a partial answer");
        assertThat(stateOf(id)).isEqualTo("FOLLOW_UP");

        JsonNode finished = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));

        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(transitionLog(id)).endsWith("FOLLOW_UP>FINAL_EVALUATION:END_REQUESTED", "FINAL_EVALUATION>COMPLETED:REPORT_READY");
        assertThat(jdbc.sql("SELECT resume_state IS NULL AND current_question_id IS NULL AND memory -> 'pendingStep' IS NULL FROM interviews WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void finishingAnInterviewThatIsNotRunningOrNotStartedIsRefusedWithItsState() throws Exception {
        UUID ada = newUser();
        UUID notStarted = createInterview(ada);
        UUID abandoned = createInterview(ada);
        postAs(ada, "/interviews/" + abandoned + "/abandon").andExpect(status().isOk());
        UUID running = startedInterview(ada);

        for (String reason : List.of("CANDIDATE_ENDED", "ACKNOWLEDGE_COMPLETE")) {
            finish(ada, notStarted, reason).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("INIT"));
            finish(ada, abandoned, reason).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("ABANDONED"));
        }
        finish(ada, running, "ACKNOWLEDGE_COMPLETE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("INTRODUCTION"));

        assertThat(stateOf(notStarted)).isEqualTo("INIT");
        assertThat(stateOf(abandoned)).isEqualTo("ABANDONED");
        assertThat(stateOf(running)).as("a refused finish changes nothing").isEqualTo("INTRODUCTION");
        assertThat(transitionLog(running)).hasSize(2);
        assertThat(narrator.calls() + evaluator.calls()).isZero();
    }

    @Test
    void acknowledgingACompleteQuestionPhaseProducesTheReportAndEitherReasonIsAcceptedOnceItIsOver() throws Exception {
        evaluator.quality(Quality.INCORRECT);
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(15));
        playAll(ada, id);
        assertThat(stateOf(id)).isEqualTo("FINAL_EVALUATION");

        JsonNode finished = body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");

        // COMPLETED already: both reasons give the same answer.
        assertThat(body(finish(ada, id, "ACKNOWLEDGE_COMPLETE"))).isEqualTo(finished);
        assertThat(body(finish(ada, id, "CANDIDATE_ENDED"))).isEqualTo(finished);
    }

    @Test
    void finishingTwiceTouchesNothingTheSecondTime() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "an answer");
        JsonNode first = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));
        int evaluatorCalls = evaluator.calls();
        int narratorCalls = narrator.calls();
        int generatorCalls = generator.calls();
        List<String> log = transitionLog(id);

        JsonNode second = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(evaluator.calls()).isEqualTo(evaluatorCalls);
        assertThat(narrator.calls()).isEqualTo(narratorCalls);
        assertThat(generator.calls()).isEqualTo(generatorCalls);
        assertThat(transitionLog(id)).isEqualTo(log);
    }

    @Test
    void simultaneousFinishesProduceOneEndOneReportAndTheSameAnswerForEveryone() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "an answer");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<MvcResult> call = () -> {
                    go.await();
                    return finish(ada, id, "CANDIDATE_ENDED").andReturn();
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(120, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                assertThat(result.getResponse().getContentAsString()).contains("\"state\":\"COMPLETED\"").contains("\"reportStatus\":\"READY\"");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(transitionLog(id).stream().filter(row -> row.endsWith("END_REQUESTED"))).hasSize(1);
        assertThat(transitionLog(id).stream().filter(row -> row.endsWith("REPORT_READY"))).hasSize(1);
        assertThat(narrator.calls()).isLessThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reports WHERE interview_id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aReportWithoutNarrativeIsStillReadyWhenTheNarratorIsDown() throws Exception {
        narrator.unavailable();
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "an answer");
        answerCurrent(ada, id, "another answer");

        JsonNode finished = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));

        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("summary").isNull()).isTrue();
        assertThat(report.get("overallScore").isNull()).isFalse();
    }

    @Test
    void theTranscriptShowsFeedbackOnlyOnceTheInterviewIsCompleted() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "an answer");
        postAs(ada, "/interviews/" + id + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}").andExpect(status().isOk());

        JsonNode transcript = body(getAs(ada, "/interviews/" + id + "/transcript").andExpect(status().isOk()));

        assertThat(transcript.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(transcript.get("entries").get(0).get("feedback").isNull()).isFalse();
        assertThat(transcript.get("entries").get(0).get("answer").get("content").asString()).isNotBlank();
    }
}
