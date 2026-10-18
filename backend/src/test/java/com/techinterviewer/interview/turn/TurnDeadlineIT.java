package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The lazy deadline in the turn (decision D8): the grace window, the exact edges, and what time-up leaves behind. */
class TurnDeadlineIT extends TurnApiTestBase {

    private static final Duration GRACE = Duration.ofSeconds(30);

    private record Setup(UUID user, UUID interview, UUID question, Instant deadline) {
    }

    /** A 45 minute interview whose introduction was answered, with its first technical question open. */
    private Setup openQuestion() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        Instant deadline = Instant.parse(session(ada, id).get("deadlineAt").asString());
        assertThat(deadline).isEqualTo(clock.instant().plus(Duration.ofMinutes(45)));
        answerIntroduction(ada, id);
        return new Setup(ada, id, currentQuestionId(ada, id), deadline);
    }

    @Test
    void anAnswerExactlyAtTheDeadlineIsNotLateItEndsTheQuestionPhaseByThePlanBecauseTheSectionBudgetIsUsedUp() throws Exception {
        Setup s = openQuestion();
        clock.set(s.deadline());

        JsonNode response = answerOk(s.user(), s.interview(), s.question(), "an answer");

        assertThat(response.get("accepted").asBoolean()).isTrue();
        assertThat(response.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(transitionLog(s.interview()).getLast()).as("not a time-up: the deadline itself is not late").isEqualTo("QUESTIONING>FINAL_EVALUATION:SECTION_COMPLETE");
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", s.interview()).query(String.class).single())
                .isEqualTo("PLAN_COMPLETE");
        assertThat(count("evaluations", s.interview())).isEqualTo(2);
    }

    @Test
    void anAnswerOneSecondAfterTheDeadlineIsLateButAcceptedAndEndsTheInterviewByTimeUp() throws Exception {
        Setup s = openQuestion();
        clock.set(s.deadline().plusSeconds(1));

        JsonNode response = answerOk(s.user(), s.interview(), s.question(), "an answer");

        assertThat(response.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(transitionLog(s.interview()).getLast()).isEqualTo("QUESTIONING>FINAL_EVALUATION:TIME_UP");
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", s.interview()).query(String.class).single())
                .isEqualTo("TIME_UP");
    }

    @Test
    void anAnswerOneSecondBeforeTheEndOfTheGraceWindowIsAcceptedAndThenTheInterviewIsOver() throws Exception {
        Setup s = openQuestion();
        clock.set(s.deadline().plus(GRACE).minusSeconds(1));
        int calls = generator.calls();

        JsonNode response = answerOk(s.user(), s.interview(), s.question(), "a late but accepted answer");

        assertThat(response.get("accepted").asBoolean()).isTrue();
        assertThat(response.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(response.get("interviewComplete").asBoolean()).isTrue();
        assertThat(response.get("nextQuestion").isNull()).isTrue();
        assertThat(response.get("awaitingNextQuestion").asBoolean()).isFalse();
        assertThat(generator.calls()).as("no question is generated for an interview that is over").isEqualTo(calls);
        assertThat(count("evaluations", s.interview())).as("the late answer was evaluated like any other").isEqualTo(2);
        assertThat(transitionLog(s.interview()).getLast()).isEqualTo("QUESTIONING>FINAL_EVALUATION:TIME_UP");
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", s.interview()).query(String.class).single())
                .isEqualTo("TIME_UP");
        assertThat(jdbc.sql("SELECT ended_at FROM interviews WHERE id = :id").param("id", s.interview()).query(java.sql.Timestamp.class).single()
                .toInstant()).as("the interview ended at its deadline, not when the last answer was processed").isEqualTo(s.deadline());
        assertThat(jdbc.sql("SELECT occurred_at FROM interview_state_transitions WHERE interview_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", s.interview()).query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(s.deadline());
        assertThat(jdbc.sql("SELECT current_question_id IS NULL AND memory -> 'pendingStep' IS NULL FROM interviews WHERE id = :id")
                .param("id", s.interview()).query(Boolean.class).single()).isTrue();

        JsonNode finished = body(finish(s.user(), s.interview(), "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        JsonNode report = body(getAs(s.user(), "/interviews/" + s.interview() + "/report").andExpect(status().isOk()));
        assertThat(report.get("coverage").get("minutesUsed").asInt()).as("45 minutes were available and used").isEqualTo(45);
    }

    @Test
    void anAnswerExactlyAtTheEndOfTheGraceWindowIsStillAccepted() throws Exception {
        Setup s = openQuestion();
        clock.set(s.deadline().plus(GRACE));

        JsonNode response = answerOk(s.user(), s.interview(), s.question(), "an answer at the last moment");

        assertThat(response.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(count("answers", s.interview())).isEqualTo(2);
    }

    @Test
    void anAnswerAfterTheGraceWindowIsRefusedTheTimeUpIsRecordedAndTheInterviewCanStillBeFinished() throws Exception {
        Setup s = openQuestion();
        clock.set(s.deadline().plus(GRACE).plusSeconds(1));
        int evaluatorCalls = evaluator.calls();

        answer(s.user(), s.interview(), s.question(), "too late")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVIEW_EXPIRED"))
                .andExpect(jsonPath("$.currentState").value("FINAL_EVALUATION"));

        assertThat(count("answers", s.interview())).as("the late answer was not stored").isEqualTo(1);
        assertThat(evaluator.calls()).isEqualTo(evaluatorCalls);
        assertThat(stateOf(s.interview())).isEqualTo("FINAL_EVALUATION");
        assertThat(transitionLog(s.interview()).getLast()).isEqualTo("QUESTIONING>FINAL_EVALUATION:TIME_UP");
        assertThat(jdbc.sql("SELECT details ->> 'deadlineAt' FROM interview_state_transitions WHERE interview_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", s.interview()).query(String.class).single()).isEqualTo(s.deadline().toString());
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", s.interview()).query(String.class).single())
                .isEqualTo("TIME_UP");

        // Every later attempt says the same, and exactly one TIME_UP was ever recorded.
        answer(s.user(), s.interview(), s.question(), "still too late").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTERVIEW_EXPIRED"));
        postAs(s.user(), "/interviews/" + s.interview() + "/questions/" + s.question() + "/skip").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVIEW_EXPIRED"));
        postAs(s.user(), "/interviews/" + s.interview() + "/questions/next").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVIEW_EXPIRED")).andExpect(jsonPath("$.currentState").value("FINAL_EVALUATION"));
        assertThat(transitionLog(s.interview()).stream().filter(row -> row.endsWith("TIME_UP"))).hasSize(1);

        // Finishing still produces the report from what was answered.
        JsonNode finished = body(finish(s.user(), s.interview(), "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        assertThat(count("answers", s.interview())).isEqualTo(1);
    }

    @Test
    void theTimeUpOfAnInterviewWithAFollowUpOpenIsRecordedFromTheFollowUpState() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        Setup s = openQuestion();
        answerOk(s.user(), s.interview(), s.question(), "a partial answer");
        assertThat(stateOf(s.interview())).isEqualTo("FOLLOW_UP");
        clock.set(s.deadline().plus(GRACE).plusSeconds(1));

        postAs(s.user(), "/interviews/" + s.interview() + "/questions/" + currentIdFromDatabase(s.interview()) + "/skip")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTERVIEW_EXPIRED"));

        assertThat(transitionLog(s.interview()).getLast()).isEqualTo("FOLLOW_UP>FINAL_EVALUATION:TIME_UP");
        assertThat(jdbc.sql("SELECT resume_state IS NULL FROM interviews WHERE id = :id").param("id", s.interview()).query(Boolean.class).single()).isTrue();
    }

    private UUID currentIdFromDatabase(UUID interview) {
        return jdbc.sql("SELECT current_question_id FROM interviews WHERE id = :id").param("id", interview).query(UUID.class).single();
    }

    @Test
    void aFollowUpAnsweredInTheGraceWindowEndsTheInterviewFromTheFollowUpState() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        Setup s = openQuestion();
        answerOk(s.user(), s.interview(), s.question(), "a partial answer");
        clock.set(s.deadline().plusSeconds(5));

        JsonNode response = answerOk(s.user(), s.interview(), currentIdFromDatabase(s.interview()), "the follow-up answer");

        assertThat(response.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(transitionLog(s.interview()).getLast()).isEqualTo("FOLLOW_UP>FINAL_EVALUATION:TIME_UP");
    }

    @Test
    void readingAnInterviewAfterItsTimeRanOutShowsItAsOverAndRecordsTheTimeUpOnce() throws Exception {
        Setup s = openQuestion();
        clock.set(s.deadline().plusSeconds(10));
        JsonNode inGrace = session(s.user(), s.interview());
        assertThat(inGrace.get("state").asString()).as("inside the grace window the question is still open").isEqualTo("QUESTIONING");
        assertThat(inGrace.get("progress").get("remainingSeconds").asLong()).isZero();

        clock.set(s.deadline().plus(GRACE).plusSeconds(1));
        for (int i = 0; i < 3; i++) {
            JsonNode over = session(s.user(), s.interview());
            assertThat(over.get("state").asString()).isEqualTo("FINAL_EVALUATION");
            assertThat(over.get("interviewComplete").asBoolean()).isTrue();
            assertThat(over.get("currentQuestion").isNull()).isTrue();
        }
        assertThat(transitionLog(s.interview()).stream().filter(row -> row.endsWith("TIME_UP"))).hasSize(1);
    }
}
