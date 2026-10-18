package com.techinterviewer.interview.engine;

import com.techinterviewer.interview.domain.Section;
import org.jspecify.annotations.Nullable;

/**
 * The facts a transition may depend on (ARCHITECTURE.md section 5, "Guards"). They are computed by the caller
 * (normally {@link TurnDecider}) from the plan, the memory and the follow-up policy; the state machine only
 * applies them.
 *
 * @param followUpAllowed whether a follow-up question follows the answer (policy says yes and the depth limit is not reached)
 * @param slotsRemain     whether the current section still has question slots (and time) for another root question
 * @param nextSection     the section the plan runs after the current one; null when the current section is the last
 * @param resumeSection   the section a follow-up thread returns to: the section it started in while in FOLLOW_UP
 */
public record TransitionGuards(
        boolean followUpAllowed,
        boolean slotsRemain,
        @Nullable Section nextSection,
        @Nullable Section resumeSection) {

    /** No guard is satisfied and there is no next or resume section. */
    public static final TransitionGuards NONE = new TransitionGuards(false, false, null, null);

    public TransitionGuards withFollowUpAllowed(boolean allowed) {
        return new TransitionGuards(allowed, slotsRemain, nextSection, resumeSection);
    }

    public TransitionGuards withSlotsRemain(boolean remain) {
        return new TransitionGuards(followUpAllowed, remain, nextSection, resumeSection);
    }

    public TransitionGuards withNextSection(@Nullable Section section) {
        return new TransitionGuards(followUpAllowed, slotsRemain, section, resumeSection);
    }

    public TransitionGuards withResumeSection(@Nullable Section section) {
        return new TransitionGuards(followUpAllowed, slotsRemain, nextSection, section);
    }
}
