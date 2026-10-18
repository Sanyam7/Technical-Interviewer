package com.techinterviewer.interview.engine;

import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import org.jspecify.annotations.Nullable;

/**
 * A state machine event that the current state does not accept (or whose guard is not satisfied). The service layer
 * maps it to {@code 409 ILLEGAL_STATE_TRANSITION} with the current state in the problem document.
 */
public final class IllegalTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final @Nullable InterviewState from;
    private final InterviewEvent event;

    public IllegalTransitionException(@Nullable InterviewState from, InterviewEvent event, String reason) {
        super("event " + event + " is not allowed in state " + (from == null ? "none" : from.name()) + ": " + reason);
        this.from = from;
        this.event = event;
    }

    /** The state the event was fired in; null for the creation edge. */
    public @Nullable InterviewState from() {
        return from;
    }

    public InterviewEvent event() {
        return event;
    }
}
