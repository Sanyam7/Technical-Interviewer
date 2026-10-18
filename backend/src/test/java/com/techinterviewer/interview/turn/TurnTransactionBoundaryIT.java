package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.report.ReportService;
import com.techinterviewer.evaluation.service.EvaluationService;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.TopicRef;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;

/** Decision D9: no database transaction is open while an AI port is called. The scripted ports watch for one. */
class TurnTransactionBoundaryIT extends TurnApiTestBase {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private ReportService reportService;

    @Test
    void theWatchersOfThePortsReallySeeATransactionWhenOneIsOpen() {
        QuestionRequest request = new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING, QuestionKind.TECHNICAL,
                new TopicRef("java", "Java", "concurrency", "Concurrency"), 3, TargetRole.BACKEND, ExperienceLevel.MID, null, List.of("Java"),
                List.of(), List.of(), null);

        transaction.executeWithoutResult(status -> generator.generate(request));
        generator.generate(request);

        assertThat(generator.transactionViolations()).as("only the call inside the transaction is a violation").hasSize(1);
        generator.reset();
    }

    @Test
    void everyPortCallOfAWholeInterviewHappensWithNoTransactionOpen() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(15));
        generator.unavailable();
        answerIntroduction(ada, id);
        generator.available();
        postAs(ada, "/interviews/" + id + "/questions/next");
        play(ada, id, Duration.ofSeconds(10), TurnApiTestBase::answerFor);
        evaluator.unavailable();
        UUID other = startedInterview(ada, technical(15));
        answerIntroduction(ada, other);
        body(finish(ada, other, "CANDIDATE_ENDED"));
        evaluator.available();
        postAs(ada, "/interviews/" + other + "/report/retry");
        body(finish(ada, id, "ACKNOWLEDGE_COMPLETE"));

        assertThat(generator.calls()).isPositive();
        assertThat(evaluator.calls()).isPositive();
        assertThat(narrator.calls()).isPositive();
        assertThat(generator.transactionViolations()).isEmpty();
        assertThat(evaluator.transactionViolations()).isEmpty();
        assertThat(narrator.transactionViolations()).isEmpty();
    }

    @Test
    void theOperationsThatCallPortsRefuseToRunInsideATransaction() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID answer = jdbc.sql("SELECT id FROM answers WHERE interview_id = :id").param("id", id).query(UUID.class).single();
        postAs(ada, "/interviews/" + id + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}");

        assertThatThrownBy(() -> transaction.execute(status -> evaluationService.beginAttempt(id, answer))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.execute(status -> evaluationService.evaluatePending(id))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.execute(status -> reportService.retry(ada, id))).isInstanceOf(IllegalTransactionStateException.class);
        JsonNode state = session(ada, id);
        assertThat(state.get("state").asString()).isEqualTo("COMPLETED");
    }
}
