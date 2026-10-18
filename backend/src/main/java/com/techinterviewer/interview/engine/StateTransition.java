package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import org.jspecify.annotations.Nullable;

/**
 * One edge of the state machine that was taken: what the interview row and the {@code interview_state_transitions}
 * log must record.
 *
 * @param from          the state before; null only for the creation edge
 * @param event         the event that fired
 * @param to            the state after
 * @param resumeSection the new value of {@code interviews.resume_state}: the section a follow-up thread returns to
 *                      while the state is FOLLOW_UP, null in every other state
 * @param endReason     why the interview ended when this edge ends it (FINAL_EVALUATION reached or ABANDONED), else null
 */
public record StateTransition(
        @Nullable InterviewState from,
        InterviewEvent event,
        InterviewState to,
        @Nullable Section resumeSection,
        @Nullable EndReason endReason) {

    public StateTransition {
        Require.nonNull(event, "event");
        Require.nonNull(to, "to");
        if ((to == InterviewState.FOLLOW_UP) != (resumeSection != null)) {
            throw new IllegalArgumentException("a resume section is required in FOLLOW_UP and not allowed elsewhere");
        }
        if (resumeSection == Section.INTRODUCTION) {
            throw new IllegalArgumentException("a follow-up thread cannot resume the introduction");
        }
        if (from == null && (event != InterviewEvent.CREATE || to != InterviewState.INIT)) {
            throw new IllegalArgumentException("only the creation edge has no from state");
        }
    }
}
