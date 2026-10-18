package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.ForbiddenFields;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AnswerBlockedException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What a turn does with the evaluation policy: a failed evaluation counts as an attempt, a refusal is final, the interview carries on
 * either way, and nothing about attempts or unassessable answers is shown to the candidate while the interview runs.
 */
class TurnGiveUpIT extends TurnApiTestBase {

    private Map<String, Object> answerRow(UUID interview, UUID question) {
        return jdbc.sql("SELECT evaluation_status, evaluation_attempts, evaluation_failure, next_evaluation_at FROM answers WHERE question_id = :q")
                .param("q", question).query().singleRow();
    }

    @Test
    void aFailedEvaluationDuringTheInterviewIsTheFirstAttemptAndSchedulesTheNextOne() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        evaluator.scriptNext(request -> {
            throw new AiUnavailableException("outage");
        });

        JsonNode response = answerOk(ada, id, question, "an answer");

        assertThat(response.get("accepted").asBoolean()).isTrue();
        assertThat(response.get("nextQuestion").isNull()).isFalse();
        Map<String, Object> row = answerRow(id, question);
        assertThat(row.get("evaluation_status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("evaluation_attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("evaluation_failure")).isEqualTo("PROVIDER_FAILURE");
        assertThat(((Timestamp) row.get("next_evaluation_at")).toInstant()).isEqualTo(clock.instant().plus(Duration.ofSeconds(2)));
        assertThat(count("evaluations", id)).as("only the introduction was evaluated").isEqualTo(1);
    }

    @Test
    void anInvalidAssessmentDuringTheInterviewIsAnAttemptWithItsOwnReason() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        evaluator.scriptNext(request -> {
            throw new IllegalArgumentException("rating must be between 0 and 4 but was 9");
        });

        answerOk(ada, id, question, "an answer");

        Map<String, Object> row = answerRow(id, question);
        assertThat(row.get("evaluation_status")).isEqualTo("PENDING");
        assertThat(row.get("evaluation_failure")).isEqualTo("INVALID_OUTPUT");
        assertThat(((Number) row.get("evaluation_attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void aSafetyBlockDuringTheInterviewMakesTheAnswerUnassessableAtOnceAndTheInterviewCarriesOn() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(30));
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        evaluator.scriptNext(request -> {
            throw new AnswerBlockedException("blocked");
        });

        JsonNode response = answerOk(ada, id, question, "an answer");

        assertThat(response.get("accepted").asBoolean()).isTrue();
        assertThat(response.get("nextQuestion").isNull()).as("no follow-up is made from an answer nobody could judge").isFalse();
        assertThat(response.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        Map<String, Object> row = answerRow(id, question);
        assertThat(row.get("evaluation_status")).isEqualTo("UNASSESSABLE");
        assertThat(row.get("evaluation_failure")).isEqualTo("SAFETY_BLOCK");
        assertThat(((Number) row.get("evaluation_attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("next_evaluation_at")).isNull();
        assertThat(count("evaluations", id)).isEqualTo(1);

        // The rest of the interview is played and finished: the refused answer is disclosed, the others are scored.
        playAll(ada, id);
        JsonNode finished = body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("assessment").get("unassessableAnswers")).hasSize(1);
        assertThat(report.get("assessment").get("unassessableAnswers").get(0).get("questionId").asString()).isEqualTo(question.toString());
        assertThat(report.get("assessment").get("unassessableAnswers").get(0).get("reason").asString()).isEqualTo("SAFETY_BLOCK");
        assertThat(report.get("outcome").asString()).isIn("PARTIAL", "INCONCLUSIVE");
        JsonNode transcript = body(getAs(ada, "/interviews/" + id + "/transcript").andExpect(status().isOk()));
        assertThat(ForbiddenFields.find(transcript, true)).isEmpty();
    }

    @Test
    void nothingAboutAttemptsOrUnassessableAnswersIsShownWhileTheInterviewRuns() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(30));
        answerIntroduction(ada, id);
        UUID blocked = currentQuestionId(ada, id);
        evaluator.scriptNext(request -> {
            throw new AnswerBlockedException("blocked");
        });
        JsonNode response = answerOk(ada, id, blocked, "an answer");
        evaluator.scriptNext(request -> {
            throw new AiUnavailableException("outage");
        });
        JsonNode second = answerOk(ada, id, currentQuestionId(ada, id), "another answer");

        List<String> documents = List.of(
                response.toString(),
                second.toString(),
                session(ada, id).toString(),
                body(getAs(ada, "/interviews/" + id + "/state").andExpect(status().isOk())).toString(),
                body(getAs(ada, "/interviews/" + id).andExpect(status().isOk())).toString());
        for (String document : documents) {
            assertThat(document.toLowerCase()).doesNotContain("unassessable", "attempt", "safety", "block", "evaluation", "provider_failure", "coverage");
        }
    }

    @Test
    void theAttemptsOfTheTurnsCountTowardsTheLimitAtTheFinish() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(15));
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        evaluator.unavailable();
        answerOk(ada, id, question, "an answer");
        int callsAfterTurn = evaluator.calls();

        JsonNode finished = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));

        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        assertThat(evaluator.calls() - callsAfterTurn).as("two more attempts at the finish, three in all for that answer").isEqualTo(2);
        Map<String, Object> row = answerRow(id, question);
        assertThat(row.get("evaluation_status")).isEqualTo("UNASSESSABLE");
        assertThat(((Number) row.get("evaluation_attempts")).intValue()).isEqualTo(3);
    }
}
