package com.techinterviewer.evaluation.report;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.interview.domain.InterviewState;

/**
 * The outcome of {@link ReportFinalizer#finalizeInterview}: the interview state afterwards (COMPLETED when the
 * report is ready, otherwise still FINAL_EVALUATION), the report status, and whether repeating the call can help.
 */
public record FinalizationResult(InterviewState state, ReportStatus reportStatus, boolean retryable) {

    public FinalizationResult {
        Require.nonNull(state, "state");
        Require.nonNull(reportStatus, "reportStatus");
        if (state != InterviewState.FINAL_EVALUATION && state != InterviewState.COMPLETED) {
            throw new IllegalArgumentException("a finalised interview is FINAL_EVALUATION or COMPLETED but was " + state);
        }
        if (state == InterviewState.COMPLETED && reportStatus != ReportStatus.READY) {
            throw new IllegalArgumentException("a COMPLETED interview has a READY report but was " + reportStatus);
        }
        if (retryable && reportStatus == ReportStatus.READY) {
            throw new IllegalArgumentException("a READY report is not retryable");
        }
    }
}
