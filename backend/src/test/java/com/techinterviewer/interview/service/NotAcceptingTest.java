package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Why an interview that asks no questions refuses an answer: time is up, or it is simply in another state. */
class NotAcceptingTest {

    private static final Instant NOW = Fixtures.NOW;

    private static Interview in(InterviewState target, InterviewEvent endEvent) {
        Interview interview = Interview.create(UUID.randomUUID(), UUID.randomUUID(), Fixtures.plan(), NOW);
        if (target == InterviewState.INIT) {
            return interview;
        }
        interview.start(NOW, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, NOW);
        if (target == InterviewState.INTRODUCTION) {
            return interview;
        }
        interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.FINAL_EVALUATION, endEvent, NOW);
        if (target == InterviewState.COMPLETED) {
            interview.applyTransition(InterviewState.FINAL_EVALUATION, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, NOW);
        }
        return interview;
    }

    private static void assertConflict(ApiException error, ErrorCode code, InterviewState state) {
        assertThat(error.errorCode()).isEqualTo(code);
        assertThat(error.properties()).containsEntry("currentState", state.name());
    }

    @Test
    void anInterviewThatThisRequestJustEndedByTimeUpIsExpired() {
        assertConflict(NotAccepting.error(in(InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP), true), ErrorCode.INTERVIEW_EXPIRED, InterviewState.FINAL_EVALUATION);
    }

    @Test
    void whatThisRequestJustEndedByTimeUpIsExpiredWhateverTheStateItIsReadInAfterwards() {
        assertConflict(NotAccepting.error(in(InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED), true), ErrorCode.INTERVIEW_EXPIRED,
                InterviewState.FINAL_EVALUATION);
        assertConflict(NotAccepting.error(in(InterviewState.COMPLETED, InterviewEvent.END_REQUESTED), true), ErrorCode.INTERVIEW_EXPIRED,
                InterviewState.COMPLETED);
    }

    @Test
    void anInterviewAwaitingItsReportAfterATimeUpIsStillExpiredForTheNextRequest() {
        assertConflict(NotAccepting.error(in(InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP), false), ErrorCode.INTERVIEW_EXPIRED, InterviewState.FINAL_EVALUATION);
    }

    @Test
    void anInterviewEndedByTheCandidateIsNotExpiredButInTheWrongState() {
        assertConflict(NotAccepting.error(in(InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED), false), ErrorCode.ILLEGAL_STATE_TRANSITION,
                InterviewState.FINAL_EVALUATION);
    }

    @Test
    void aCompletedInterviewIsInTheWrongStateEvenIfItEndedByTimeUp() {
        assertConflict(NotAccepting.error(in(InterviewState.COMPLETED, InterviewEvent.TIME_UP), false), ErrorCode.ILLEGAL_STATE_TRANSITION, InterviewState.COMPLETED);
    }

    @Test
    void anInterviewThatWasNotStartedIsInTheWrongState() {
        assertConflict(NotAccepting.error(in(InterviewState.INIT, null), false), ErrorCode.ILLEGAL_STATE_TRANSITION, InterviewState.INIT);
    }

    @Test
    void theDetailNeverRevealsAnythingButTheState() {
        ApiException expired = NotAccepting.error(in(InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP), true);
        ApiException wrong = NotAccepting.error(in(InterviewState.INIT, null), false);

        assertThat(expired.getMessage()).isEqualTo(NotAccepting.EXPIRED_DETAIL);
        assertThat(wrong.getMessage()).isEqualTo("The interview is INIT and asks no questions.");
        assertThat(wrong.properties()).containsOnlyKeys("currentState");
    }
}
