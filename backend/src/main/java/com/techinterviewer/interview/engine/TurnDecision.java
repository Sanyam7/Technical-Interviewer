package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What happens after an answer: the state transitions to record, the question to generate next, and the updated memory.
 *
 * <p>Almost always there is one transition. A follow-up thread that ends exactly when its section is used up takes
 * two in the same turn, FOLLOW_UP_DONE and then SECTION_COMPLETE; the service records both in order and stores the
 * last one's target state.
 *
 * @param transitions the edges taken, in order; at least one, each starting where the previous one ended
 * @param pendingStep the question to generate next; null when the interview has reached FINAL_EVALUATION
 * @param memory      the memory after the answer; its {@code pendingStep} is {@code pendingStep}
 * @param rationale   why: short human-readable lines keyed by aspect ({@code performance}, {@code difficulty},
 *                    {@code followUp}, {@code next}, {@code time}), recorded with the turn's last transition
 */
public record TurnDecision(
        List<StateTransition> transitions, @Nullable PendingStep pendingStep, InterviewMemory memory, Map<String, String> rationale) {

    public TurnDecision {
        transitions = Immutables.list(transitions);
        rationale = Collections.unmodifiableMap(new LinkedHashMap<>(Require.nonNull(rationale, "rationale")));
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException("a decision has at least one transition");
        }
        for (int i = 1; i < transitions.size(); i++) {
            if (transitions.get(i).from() != transitions.get(i - 1).to()) {
                throw new IllegalArgumentException("transitions must form a chain");
            }
        }
        Require.nonNull(memory, "memory");
        if (!Objects.equals(memory.pendingStep(), pendingStep)) {
            throw new IllegalArgumentException("the memory must hold the pending step");
        }
        if ((pendingStep == null) != (transitions.getLast().to() == InterviewState.FINAL_EVALUATION)) {
            throw new IllegalArgumentException("a question is pending exactly when the interview continues");
        }
    }

    public TurnDecision(List<StateTransition> transitions, @Nullable PendingStep pendingStep, InterviewMemory memory) {
        this(transitions, pendingStep, memory, Map.of());
    }

    /** The event that led to the final state (the last of the transitions). */
    public InterviewEvent event() {
        return transitions.getLast().event();
    }

    /** The state the interview is in after the turn. */
    public InterviewState nextState() {
        return transitions.getLast().to();
    }

    /** The new {@code interviews.resume_state}: the section of the follow-up thread, null outside FOLLOW_UP. */
    public @Nullable Section resumeSection() {
        return transitions.getLast().resumeSection();
    }

    /** Why the interview ended, when this turn ended it. */
    public @Nullable EndReason endReason() {
        return transitions.getLast().endReason();
    }

    /** True when this turn ended the question phase: the interview is in FINAL_EVALUATION and no question is pending. */
    public boolean interviewComplete() {
        return pendingStep == null;
    }
}
