package com.techinterviewer.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.InterviewApiTestBase;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One {@code llm_calls} row per call, written for real; a row that cannot be written never breaks the AI operation. */
class LlmCallRecorderIT extends InterviewApiTestBase {

    @Autowired
    LlmCallRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private LlmCallRecorder recorder() {
        return new LlmCallRecorder(repository, new TransactionTemplate(transactionManager), clock);
    }

    private static LlmCallRecord record(UUID interviewId, LlmCallStatus status, String error) {
        return new LlmCallRecord(interviewId, "ANSWER_EVALUATION", "gemini", "gemini-3.8-flash", "gemini-3.8-flash-001",
                "answer-evaluation-technical", "v1", 2, status, "STOP", 1200, 300, 80, Duration.ofMillis(2345), null, error, null);
    }

    @Test
    void aCallIsStoredWithEverythingTheRecordKnows() throws Exception {
        UUID ada = newUser();
        UUID interview = startedInterview(ada);
        MDC.put("traceId", "trace-123");
        try {
            Optional<UUID> id = recorder().record(record(interview, LlmCallStatus.REPAIRED, null));

            assertThat(id).isPresent();
            Map<String, Object> row = jdbc.sql("SELECT * FROM llm_calls WHERE id = ?").param(id.get()).query().singleRow();
            assertThat(row).containsEntry("interview_id", interview).containsEntry("task", "ANSWER_EVALUATION")
                    .containsEntry("provider", "gemini").containsEntry("model", "gemini-3.8-flash")
                    .containsEntry("model_version", "gemini-3.8-flash-001").containsEntry("prompt_id", "answer-evaluation-technical")
                    .containsEntry("prompt_version", "v1").containsEntry("status", "REPAIRED").containsEntry("finish_reason", "STOP")
                    .containsEntry("input_tokens", 1200).containsEntry("output_tokens", 300).containsEntry("thinking_tokens", 80)
                    .containsEntry("latency_ms", 2345).containsEntry("trace_id", "trace-123");
            assertThat(((Number) row.get("attempt")).intValue()).isEqualTo(2);
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void anErrorMessageIsShortenedAndCleanedOfCharactersTheDatabaseCannotStore() throws Exception {
        UUID interview = startedInterview(newUser());

        UUID id = recorder().record(record(interview, LlmCallStatus.INVALID_OUTPUT, "bad\u0000output " + "x".repeat(900))).orElseThrow();

        String stored = jdbc.sql("SELECT error_message FROM llm_calls WHERE id = ?").param(id).query(String.class).single();
        assertThat(stored).hasSize(LlmCall.MAX_ERROR_MESSAGE_LENGTH).startsWith("bad output ");
    }

    @Test
    void aRowThatCannotBeWrittenIsSkippedWithoutFailingTheCall() {
        Optional<UUID> id = recorder().record(record(UUID.randomUUID(), LlmCallStatus.OK, null));

        assertThat(id).as("the interview does not exist: the foreign key rejects the row").isEmpty();
    }

    @Test
    void aCallWithoutInterviewIsStoredToo() {
        assertThat(recorder().record(record(null, LlmCallStatus.TIMEOUT, "slow"))).isPresent();
    }
}
