package com.techinterviewer.interview.service;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.report.FinalizationResult;
import com.techinterviewer.evaluation.report.ReportFinalizer;
import com.techinterviewer.interview.api.dto.FinishReason;
import com.techinterviewer.interview.api.dto.FinishResponse;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.engine.TransitionGuards;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code POST /interviews/{id}/finish}: closes the question phase and produces the report. The candidate may end a
 * running interview (END_REQUESTED, in a short transaction of its own); once the interview is in FINAL_EVALUATION the
 * report is made by the {@link ReportFinalizer} with no transaction open, because it calls the AI ports. The whole call is
 * idempotent: an interview that ended or even completed already is not touched again, and a report that could not be
 * completed is retried by the next call.
 */
@Service
public class FinishService {

    static final String REPORT_PATH = "/api/v1/interviews/%s/report";
    private static final int MAX_ATTEMPTS = 3;

    private final DeadlineService deadlines;
    private final InterviewWriter writer;
    private final TransitionApplier transitions;
    private final InterviewRepository interviews;
    private final ReportFinalizer finalizer;
    private final Clock clock;

    FinishService(
            DeadlineService deadlines,
            InterviewWriter writer,
            TransitionApplier transitions,
            InterviewRepository interviews,
            ReportFinalizer finalizer,
            Clock clock) {
        this.deadlines = deadlines;
        this.writer = writer;
        this.transitions = transitions;
        this.interviews = interviews;
        this.finalizer = finalizer;
        this.clock = clock;
    }

    /**
     * @throws ApiException {@code 404} (not the caller's interview), {@code 409 ILLEGAL_STATE_TRANSITION} when the interview was
     *                      never started or was abandoned, or when ACKNOWLEDGE_COMPLETE is sent while it still asks questions,
     *                      {@code 409 CONCURRENT_MODIFICATION} when a concurrent request kept changing the interview
     */
    public FinishResponse finish(UUID userId, UUID interviewId, FinishReason reason) {
        deadlines.expireIfDue(interviewId, userId);
        endIfRunning(userId, interviewId, reason);
        FinalizationResult result = finalizer.finalizeInterview(interviewId, userId);
        boolean ready = result.reportStatus() == ReportStatus.READY;
        return new FinishResponse(
                result.state(), result.reportStatus(), result.retryable(), ready ? REPORT_PATH.formatted(interviewId) : null);
    }

    private void endIfRunning(UUID userId, UUID interviewId, FinishReason reason) {
        for (int attempt = 1; ; attempt++) {
            try {
                writer.write(interviewId, userId, interview -> end(interview, reason));
                return;
            } catch (ApiException failure) {
                if (failure.errorCode() != ErrorCode.CONCURRENT_MODIFICATION || attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
            }
        }
    }

    private Boolean end(Interview interview, FinishReason reason) {
        InterviewState state = interview.getState();
        if (state == InterviewState.FINAL_EVALUATION || state == InterviewState.COMPLETED) {
            return Boolean.FALSE;
        }
        if (reason == FinishReason.ACKNOWLEDGE_COMPLETE) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "The interview is " + state + ": ACKNOWLEDGE_COMPLETE is valid only once its questions are over.")
                    .withProperty(TransitionApplier.CURRENT_STATE, state.name());
        }
        transitions.apply(interview, InterviewEvent.END_REQUESTED, TransitionGuards.NONE, clock.instant());
        interviews.saveAndFlush(interview);
        return Boolean.TRUE;
    }
}
