package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.report.FinalizationResult;
import com.techinterviewer.evaluation.report.ReportFinalizer;
import com.techinterviewer.interview.api.dto.FinishReason;
import com.techinterviewer.interview.api.dto.FinishResponse;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.persistence.Fixtures;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How finishing reacts to a lost race (it tries again, a few times) and to every other refusal (it does not). */
class FinishServiceTest {

    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d51");
    private static final UUID INTERVIEW = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d52");

    private final InterviewWriter writer = mock(InterviewWriter.class);
    private final ReportFinalizer finalizer = mock(ReportFinalizer.class);
    private final AtomicInteger writes = new AtomicInteger();
    private FinishService service;

    @BeforeEach
    void setUp() {
        service = new FinishService(
                mock(DeadlineService.class), writer, mock(TransitionApplier.class), mock(InterviewRepository.class), finalizer, new TestClock(Fixtures.NOW));
        when(finalizer.finalizeInterview(INTERVIEW, USER)).thenReturn(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
    }

    private static ApiException refusal(ErrorCode code) {
        return new ApiException(code, "refused");
    }

    @Test
    void aRefusalOfTheInterviewIsReportedAtOnceWithoutAnotherAttempt() {
        when(writer.write(any(), any(), any())).thenAnswer(call -> {
            writes.incrementAndGet();
            throw refusal(ErrorCode.ILLEGAL_STATE_TRANSITION);
        });

        assertThatThrownBy(() -> service.finish(USER, INTERVIEW, FinishReason.ACKNOWLEDGE_COMPLETE))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION));
        assertThat(writes).hasValue(1);
    }

    @Test
    void aLostRaceIsRetriedAndTheLaterSuccessGivesTheReport() {
        when(writer.write(any(), any(), any())).thenAnswer(call -> {
            if (writes.incrementAndGet() < 3) {
                throw refusal(ErrorCode.CONCURRENT_MODIFICATION);
            }
            return Boolean.TRUE;
        });

        FinishResponse response = service.finish(USER, INTERVIEW, FinishReason.CANDIDATE_ENDED);

        assertThat(writes).hasValue(3);
        assertThat(response.state()).isEqualTo(InterviewState.COMPLETED);
        assertThat(response.reportStatus()).isEqualTo(ReportStatus.READY);
        assertThat(response.reportUrl()).isEqualTo("/api/v1/interviews/" + INTERVIEW + "/report");
    }

    @Test
    void aRaceThatIsLostEveryTimeIsAConcurrentModificationAfterThreeAttempts() {
        when(writer.write(any(), any(), any())).thenAnswer(call -> {
            writes.incrementAndGet();
            throw refusal(ErrorCode.CONCURRENT_MODIFICATION);
        });

        assertThatThrownBy(() -> service.finish(USER, INTERVIEW, FinishReason.CANDIDATE_ENDED))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        assertThat(writes).hasValue(3);
    }

    @Test
    void aReportThatCannotBeCompletedHasNoUrlAndCanBeRetried() {
        when(writer.write(any(), any(), any())).thenReturn(Boolean.FALSE);
        when(finalizer.finalizeInterview(INTERVIEW, USER)).thenReturn(new FinalizationResult(InterviewState.FINAL_EVALUATION, ReportStatus.FAILED, true));

        FinishResponse response = service.finish(USER, INTERVIEW, FinishReason.CANDIDATE_ENDED);

        assertThat(response.state()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(response.reportStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(response.retryable()).isTrue();
        assertThat(response.reportUrl()).isNull();
    }
}
