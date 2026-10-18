package com.techinterviewer.interview.service;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewState;

/**
 * The refusal of an action that needs an interview that is asking questions, when it is not: {@code 409
 * INTERVIEW_EXPIRED} when the time ran out (this very request found out, or the interview waits for its report after a
 * time-up), otherwise {@code 409 ILLEGAL_STATE_TRANSITION}. Both carry the state the interview is in.
 */
final class NotAccepting {

    static final String EXPIRED_DETAIL = "The time for this interview is up. Finish it to get the report.";

    private NotAccepting() {
    }

    /**
     * @param expiredNow this request applied the lazy deadline: the interview was ended by TIME_UP just now
     */
    static ApiException error(Interview interview, boolean expiredNow) {
        InterviewState state = interview.getState();
        boolean timeUp = expiredNow || (state == InterviewState.FINAL_EVALUATION && interview.getEndReason() == EndReason.TIME_UP);
        if (timeUp) {
            return new ApiException(ErrorCode.INTERVIEW_EXPIRED, EXPIRED_DETAIL).withProperty(TransitionApplier.CURRENT_STATE, state.name());
        }
        return new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, "The interview is " + state + " and asks no questions.")
                .withProperty(TransitionApplier.CURRENT_STATE, state.name());
    }
}
