package com.techinterviewer.interview.service;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.engine.IllegalTransitionException;
import com.techinterviewer.interview.engine.InterviewStateMachine;
import com.techinterviewer.interview.engine.StateTransition;
import com.techinterviewer.interview.engine.TransitionGuards;
import com.techinterviewer.interview.repository.InterviewStateTransitionRepository;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The only place that changes the state of an interview. The state machine of the engine decides whether the move is
 * legal, {@link Interview#applyTransition} applies it to the aggregate and the audit row of every move is saved, so
 * the transition log and the interview never disagree. It joins the transaction of the caller, which also owns the
 * version check of the interview row.
 */
@Component
public class TransitionApplier {

    static final String CURRENT_STATE = "currentState";

    private final InterviewStateTransitionRepository audit;

    TransitionApplier(InterviewStateTransitionRepository audit) {
        this.audit = audit;
    }

    /** Writes the first row of the log of a new interview: nothing to INIT, event CREATE. */
    public void recordCreation(Interview interview, Instant at) {
        audit.save(InterviewStateTransition.creation(interview.getId(), at));
    }

    /**
     * Asks the state machine for the edge {@code event} takes out of the interview's current state, without changing
     * anything yet. A caller that must prepare the interview first (it has to be started before it can move) asks
     * first and applies afterwards.
     *
     * @throws ApiException {@code 409 ILLEGAL_STATE_TRANSITION} carrying the current state when the state does not accept the event
     */
    public StateTransition edge(Interview interview, InterviewEvent event, TransitionGuards guards) {
        try {
            return InterviewStateMachine.transition(interview.getState(), event, guards);
        } catch (IllegalTransitionException ex) {
            throw illegal(interview.getState(), event, ex);
        }
    }

    /** {@link #edge} followed by {@link #apply(Interview, StateTransition, Instant, Map)} without details. */
    public InterviewStateTransition apply(Interview interview, InterviewEvent event, TransitionGuards guards, Instant at) {
        return apply(interview, edge(interview, event, guards), at, null);
    }

    /**
     * Moves the interview along {@code edge}, saves the audit row and returns it. When the new state asks no more
     * questions, a question that was decided but not produced yet is forgotten: there is nothing left to retry.
     *
     * @param at      when the move happened
     * @param details context to keep in the log row, if any
     * @throws IllegalStateException when the interview is not in the state the edge leaves (the edge is stale)
     */
    public InterviewStateTransition apply(Interview interview, StateTransition edge, Instant at, @Nullable Map<String, String> details) {
        InterviewStateTransition row = interview.applyTransition(edge.from(), edge.to(), edge.event(), details, at);
        audit.save(row);
        if (!edge.to().acceptsAnswers() && interview.getMemory().pendingStep() != null) {
            interview.replaceMemory(interview.getMemory().withPendingStep(null), at);
        }
        return row;
    }

    private static ApiException illegal(InterviewState state, InterviewEvent event, IllegalTransitionException cause) {
        return new ApiException(
                ErrorCode.ILLEGAL_STATE_TRANSITION,
                "The " + event + " action is not allowed while the interview is " + state + ".",
                cause)
                .withProperty(CURRENT_STATE, state.name());
    }
}
