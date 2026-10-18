package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.domain.InterviewEvent.ABANDON;
import static com.techinterviewer.interview.domain.InterviewEvent.ANSWER_EVALUATED;
import static com.techinterviewer.interview.domain.InterviewEvent.END_REQUESTED;
import static com.techinterviewer.interview.domain.InterviewEvent.FOLLOW_UP_DONE;
import static com.techinterviewer.interview.domain.InterviewEvent.INTRO_ANSWERED;
import static com.techinterviewer.interview.domain.InterviewEvent.REPORT_FAILED;
import static com.techinterviewer.interview.domain.InterviewEvent.REPORT_READY;
import static com.techinterviewer.interview.domain.InterviewEvent.SECTION_COMPLETE;
import static com.techinterviewer.interview.domain.InterviewEvent.START;
import static com.techinterviewer.interview.domain.InterviewEvent.TIME_UP;
import static com.techinterviewer.interview.domain.InterviewState.ABANDONED;
import static com.techinterviewer.interview.domain.InterviewState.CODING;
import static com.techinterviewer.interview.domain.InterviewState.COMPLETED;
import static com.techinterviewer.interview.domain.InterviewState.FINAL_EVALUATION;
import static com.techinterviewer.interview.domain.InterviewState.FOLLOW_UP;
import static com.techinterviewer.interview.domain.InterviewState.INIT;
import static com.techinterviewer.interview.domain.InterviewState.INTRODUCTION;
import static com.techinterviewer.interview.domain.InterviewState.QUESTIONING;
import static com.techinterviewer.interview.domain.InterviewState.SYSTEM_DESIGN;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The interview state machine of ARCHITECTURE.md section 5 as an explicit transition table: for every state, the
 * events it accepts and the rule (guards included) that picks the target. Everything not in the table is illegal,
 * and the terminal states (COMPLETED, ABANDONED) accept nothing.
 *
 * <pre>
 * INIT             START -> INTRODUCTION                       ABANDON -> ABANDONED
 * INTRODUCTION     INTRO_ANSWERED -> the next section          TIME_UP, END_REQUESTED -> FINAL_EVALUATION
 * QUESTIONING      ANSWER_EVALUATED -> FOLLOW_UP | QUESTIONING SECTION_COMPLETE -> next section | FINAL_EVALUATION
 * CODING           ANSWER_EVALUATED -> FOLLOW_UP | CODING      SECTION_COMPLETE -> SYSTEM_DESIGN | FINAL_EVALUATION
 * SYSTEM_DESIGN    ANSWER_EVALUATED -> FOLLOW_UP               SECTION_COMPLETE -> FINAL_EVALUATION
 * FOLLOW_UP        ANSWER_EVALUATED -> FOLLOW_UP               FOLLOW_UP_DONE -> the resume section
 * FINAL_EVALUATION REPORT_READY -> COMPLETED                   REPORT_FAILED -> FINAL_EVALUATION
 * </pre>
 *
 * <p>Additions to the diagram, both needed by the plan: CODING may repeat itself while it has slots (a plan has one
 * or two coding problems), and TIME_UP / END_REQUESTED are accepted in every state that asks questions. The
 * machine holds no state and no clock; it is a pure function of (state, event, guards).
 */
public final class InterviewStateMachine {

    /** A rule picks the edge for an event, or returns null when its guard is not satisfied. */
    @FunctionalInterface
    private interface Rule {

        @Nullable StateTransition fire(InterviewState from, InterviewEvent event, TransitionGuards guards);
    }

    private static final Set<Section> RESUMABLE = Set.of(Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);

    private static final Map<InterviewState, Map<InterviewEvent, Rule>> TABLE = buildTable();

    private InterviewStateMachine() {
    }

    /** The creation edge: an interview is born in INIT (the first row of the transition log has no from state). */
    public static StateTransition creation() {
        return new StateTransition(null, InterviewEvent.CREATE, INIT, null, null);
    }

    /**
     * Takes the edge for {@code event} out of {@code from}.
     *
     * @throws IllegalTransitionException when the state does not accept the event or the guards rule it out
     */
    public static StateTransition transition(InterviewState from, InterviewEvent event, TransitionGuards guards) {
        Require.nonNull(from, "from");
        Require.nonNull(event, "event");
        Require.nonNull(guards, "guards");
        Rule rule = TABLE.get(from).get(event);
        if (rule == null) {
            throw new IllegalTransitionException(from, event, "the state does not accept this event");
        }
        StateTransition taken = rule.fire(from, event, guards);
        if (taken == null) {
            throw new IllegalTransitionException(from, event, "the guard is not satisfied");
        }
        return taken;
    }

    /** True when the state has a rule for the event, whatever the guards say. */
    public static boolean accepts(InterviewState state, InterviewEvent event) {
        return TABLE.get(state).containsKey(event);
    }

    /** The events a state has a rule for; empty for the terminal states. */
    public static Set<InterviewEvent> acceptedEvents(InterviewState state) {
        return Set.copyOf(TABLE.get(state).keySet());
    }

    private static Map<InterviewState, Map<InterviewEvent, Rule>> buildTable() {
        Map<InterviewState, Map<InterviewEvent, Rule>> table = new EnumMap<>(InterviewState.class);
        for (InterviewState state : InterviewState.values()) {
            table.put(state, new EnumMap<>(InterviewEvent.class));
        }
        table.get(INIT).put(START, (from, event, guards) -> edge(from, event, INTRODUCTION));
        table.get(INIT).put(ABANDON, (from, event, guards) -> ending(from, event, ABANDONED, EndReason.ABANDONED));

        table.get(INTRODUCTION).put(INTRO_ANSWERED, InterviewStateMachine::afterIntroduction);

        for (InterviewState section : new InterviewState[] {QUESTIONING, CODING, SYSTEM_DESIGN}) {
            table.get(section).put(ANSWER_EVALUATED, InterviewStateMachine::afterAnswerInSection);
            table.get(section).put(SECTION_COMPLETE, InterviewStateMachine::afterSection);
        }

        table.get(FOLLOW_UP).put(ANSWER_EVALUATED, InterviewStateMachine::continueFollowUp);
        table.get(FOLLOW_UP).put(FOLLOW_UP_DONE, InterviewStateMachine::resumeSection);

        for (InterviewState state : new InterviewState[] {INTRODUCTION, QUESTIONING, FOLLOW_UP, CODING, SYSTEM_DESIGN}) {
            table.get(state).put(TIME_UP, (from, event, guards) -> ending(from, event, FINAL_EVALUATION, EndReason.TIME_UP));
            table.get(state).put(
                    END_REQUESTED, (from, event, guards) -> ending(from, event, FINAL_EVALUATION, EndReason.CANDIDATE_ENDED));
        }

        table.get(FINAL_EVALUATION).put(REPORT_READY, (from, event, guards) -> edge(from, event, COMPLETED));
        table.get(FINAL_EVALUATION).put(REPORT_FAILED, (from, event, guards) -> edge(from, event, FINAL_EVALUATION));
        return table;
    }

    /** INTRODUCTION: on to the first section of the plan after it, which must be a question-asking section. */
    private static @Nullable StateTransition afterIntroduction(
            InterviewState from, InterviewEvent event, TransitionGuards guards) {
        Section next = guards.nextSection();
        return next != null && RESUMABLE.contains(next) ? edge(from, event, next.state()) : null;
    }

    /**
     * QUESTIONING, CODING, SYSTEM_DESIGN after an evaluated answer: a follow-up wins over the next root question;
     * without either the event is illegal (the section must be completed instead). Only questioning and coding
     * repeat themselves: the design section holds a single design.
     */
    private static @Nullable StateTransition afterAnswerInSection(
            InterviewState from, InterviewEvent event, TransitionGuards guards) {
        if (guards.followUpAllowed()) {
            return followUp(from, event, sectionOf(from));
        }
        boolean mayRepeat = from == QUESTIONING || from == CODING;
        return mayRepeat && guards.slotsRemain() ? edge(from, event, from) : null;
    }

    /** SECTION_COMPLETE: the next section of the plan, or FINAL_EVALUATION after the last one. */
    private static @Nullable StateTransition afterSection(InterviewState from, InterviewEvent event, TransitionGuards guards) {
        Section next = guards.nextSection();
        if (next == null) {
            return ending(from, event, FINAL_EVALUATION, EndReason.PLAN_COMPLETE);
        }
        boolean valid = next.ordinal() > sectionOf(from).ordinal() && RESUMABLE.contains(next);
        return valid ? edge(from, event, next.state()) : null;
    }

    /** FOLLOW_UP after an evaluated answer: another follow-up in the same thread (the depth guard is in followUpAllowed). */
    private static @Nullable StateTransition continueFollowUp(
            InterviewState from, InterviewEvent event, TransitionGuards guards) {
        Section resume = guards.resumeSection();
        return guards.followUpAllowed() && resume != null && RESUMABLE.contains(resume) ? followUp(from, event, resume) : null;
    }

    /** FOLLOW_UP_DONE: back to the section the thread started in. */
    private static @Nullable StateTransition resumeSection(
            InterviewState from, InterviewEvent event, TransitionGuards guards) {
        Section resume = guards.resumeSection();
        return resume != null && RESUMABLE.contains(resume) ? edge(from, event, resume.state()) : null;
    }

    private static Section sectionOf(InterviewState state) {
        return switch (state) {
            case QUESTIONING -> Section.QUESTIONING;
            case CODING -> Section.CODING;
            case SYSTEM_DESIGN -> Section.SYSTEM_DESIGN;
            case INIT, INTRODUCTION, FOLLOW_UP, FINAL_EVALUATION, COMPLETED, ABANDONED ->
                    throw new IllegalArgumentException(state + " is not a question section");
        };
    }

    private static StateTransition edge(InterviewState from, InterviewEvent event, InterviewState to) {
        return new StateTransition(from, event, to, null, null);
    }

    private static StateTransition followUp(InterviewState from, InterviewEvent event, Section resume) {
        return new StateTransition(from, event, FOLLOW_UP, resume, null);
    }

    private static StateTransition ending(InterviewState from, InterviewEvent event, InterviewState to, EndReason reason) {
        return new StateTransition(from, event, to, null, reason);
    }
}
