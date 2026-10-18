package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.domain.InterviewEvent.ABANDON;
import static com.techinterviewer.interview.domain.InterviewEvent.ANSWER_EVALUATED;
import static com.techinterviewer.interview.domain.InterviewEvent.CREATE;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterviewStateMachineTest {

    /** Every guard combination the machine can be handed, including senseless ones. */
    private static List<TransitionGuards> allGuards() {
        List<Section> sections = new ArrayList<>();
        sections.add(null);
        sections.addAll(List.of(Section.values()));
        List<TransitionGuards> guards = new ArrayList<>();
        for (boolean followUp : new boolean[] {false, true}) {
            for (boolean slots : new boolean[] {false, true}) {
                for (Section next : sections) {
                    for (Section resume : sections) {
                        guards.add(new TransitionGuards(followUp, slots, next, resume));
                    }
                }
            }
        }
        return guards;
    }

    /** The events each state has a rule for, as documented in ARCHITECTURE.md section 5 (CREATE is the creation edge, no state accepts it). */
    private static final Map<InterviewState, Set<InterviewEvent>> DOCUMENTED_EVENTS = Map.of(
            INIT, EnumSet.of(START, ABANDON),
            INTRODUCTION, EnumSet.of(INTRO_ANSWERED, TIME_UP, END_REQUESTED),
            QUESTIONING, EnumSet.of(ANSWER_EVALUATED, SECTION_COMPLETE, TIME_UP, END_REQUESTED),
            FOLLOW_UP, EnumSet.of(ANSWER_EVALUATED, FOLLOW_UP_DONE, TIME_UP, END_REQUESTED),
            CODING, EnumSet.of(ANSWER_EVALUATED, SECTION_COMPLETE, TIME_UP, END_REQUESTED),
            SYSTEM_DESIGN, EnumSet.of(ANSWER_EVALUATED, SECTION_COMPLETE, TIME_UP, END_REQUESTED),
            FINAL_EVALUATION, EnumSet.of(REPORT_READY, REPORT_FAILED),
            COMPLETED, EnumSet.noneOf(InterviewEvent.class),
            ABANDONED, EnumSet.noneOf(InterviewEvent.class));

    /** Every edge of the diagram (plus CODING repeating itself, see the machine's comment), as "FROM EVENT TO". */
    private static final Set<String> DOCUMENTED_EDGES = Set.of(
            "INIT START INTRODUCTION",
            "INIT ABANDON ABANDONED",
            "INTRODUCTION INTRO_ANSWERED QUESTIONING",
            "INTRODUCTION INTRO_ANSWERED CODING",
            "INTRODUCTION INTRO_ANSWERED SYSTEM_DESIGN",
            "QUESTIONING ANSWER_EVALUATED FOLLOW_UP",
            "QUESTIONING ANSWER_EVALUATED QUESTIONING",
            "QUESTIONING SECTION_COMPLETE CODING",
            "QUESTIONING SECTION_COMPLETE SYSTEM_DESIGN",
            "QUESTIONING SECTION_COMPLETE FINAL_EVALUATION",
            "FOLLOW_UP ANSWER_EVALUATED FOLLOW_UP",
            "FOLLOW_UP FOLLOW_UP_DONE QUESTIONING",
            "FOLLOW_UP FOLLOW_UP_DONE CODING",
            "FOLLOW_UP FOLLOW_UP_DONE SYSTEM_DESIGN",
            "CODING ANSWER_EVALUATED FOLLOW_UP",
            "CODING ANSWER_EVALUATED CODING",
            "CODING SECTION_COMPLETE SYSTEM_DESIGN",
            "CODING SECTION_COMPLETE FINAL_EVALUATION",
            "SYSTEM_DESIGN ANSWER_EVALUATED FOLLOW_UP",
            "SYSTEM_DESIGN SECTION_COMPLETE FINAL_EVALUATION",
            "INTRODUCTION TIME_UP FINAL_EVALUATION",
            "INTRODUCTION END_REQUESTED FINAL_EVALUATION",
            "QUESTIONING TIME_UP FINAL_EVALUATION",
            "QUESTIONING END_REQUESTED FINAL_EVALUATION",
            "FOLLOW_UP TIME_UP FINAL_EVALUATION",
            "FOLLOW_UP END_REQUESTED FINAL_EVALUATION",
            "CODING TIME_UP FINAL_EVALUATION",
            "CODING END_REQUESTED FINAL_EVALUATION",
            "SYSTEM_DESIGN TIME_UP FINAL_EVALUATION",
            "SYSTEM_DESIGN END_REQUESTED FINAL_EVALUATION",
            "FINAL_EVALUATION REPORT_READY COMPLETED",
            "FINAL_EVALUATION REPORT_FAILED FINAL_EVALUATION");

    private static Set<String> edgesTakenUnderAnyGuard() {
        Set<String> edges = new HashSet<>();
        for (InterviewState from : InterviewState.values()) {
            for (InterviewEvent event : InterviewEvent.values()) {
                for (TransitionGuards guards : allGuards()) {
                    try {
                        edges.add(from + " " + event + " " + InterviewStateMachine.transition(from, event, guards).to());
                    } catch (IllegalTransitionException ignored) {
                        // not an edge
                    }
                }
            }
        }
        return edges;
    }

    @Test
    void everyStateEventPairIsClassifiedAsAcceptedOrNotAndMatchesTheDocument() {
        for (InterviewState state : InterviewState.values()) {
            for (InterviewEvent event : InterviewEvent.values()) {
                boolean documented = DOCUMENTED_EVENTS.get(state).contains(event);

                assertThat(InterviewStateMachine.accepts(state, event)).as(state + " accepts " + event).isEqualTo(documented);
            }
            assertThat(InterviewStateMachine.acceptedEvents(state)).as("events of " + state).isEqualTo(DOCUMENTED_EVENTS.get(state));
        }
    }

    @Test
    void anEventTheStateDoesNotAcceptIsIllegalUnderEveryGuardCombination() {
        for (InterviewState state : InterviewState.values()) {
            for (InterviewEvent event : InterviewEvent.values()) {
                if (DOCUMENTED_EVENTS.get(state).contains(event)) {
                    continue;
                }
                for (TransitionGuards guards : allGuards()) {
                    assertThatThrownBy(() -> InterviewStateMachine.transition(state, event, guards))
                            .as(state + " + " + event + " + " + guards)
                            .isInstanceOfSatisfying(IllegalTransitionException.class, e -> {
                                assertThat(e.from()).isEqualTo(state);
                                assertThat(e.event()).isEqualTo(event);
                            });
                }
            }
        }
    }

    @Test
    void terminalStatesRejectEveryEvent() {
        for (InterviewState terminal : List.of(COMPLETED, ABANDONED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(InterviewStateMachine.acceptedEvents(terminal)).isEmpty();
            for (InterviewEvent event : InterviewEvent.values()) {
                for (TransitionGuards guards : allGuards()) {
                    assertThatThrownBy(() -> InterviewStateMachine.transition(terminal, event, guards))
                            .isInstanceOf(IllegalTransitionException.class);
                }
            }
        }
    }

    @Test
    void theEdgesTheMachineCanTakeAreExactlyTheDocumentedOnes() {
        assertThat(edgesTakenUnderAnyGuard()).isEqualTo(DOCUMENTED_EDGES);
    }

    @Test
    void everyStateIsReachableFromInitAndNoUndocumentedStateExists() {
        Set<InterviewState> reachable = EnumSet.of(INIT);
        Deque<InterviewState> queue = new ArrayDeque<>(List.of(INIT));
        while (!queue.isEmpty()) {
            InterviewState state = queue.poll();
            for (String edge : edgesTakenUnderAnyGuard()) {
                String[] parts = edge.split(" ");
                if (parts[0].equals(state.name()) && reachable.add(InterviewState.valueOf(parts[2]))) {
                    queue.add(InterviewState.valueOf(parts[2]));
                }
            }
        }

        assertThat(reachable).containsExactlyInAnyOrder(InterviewState.values());
        assertThat(InterviewState.values()).hasSize(9);
    }

    @Test
    void finalEvaluationIsOnlyLeftThroughTheReport() {
        for (String edge : DOCUMENTED_EDGES) {
            if (edge.startsWith("FINAL_EVALUATION")) {
                assertThat(edge).matches("FINAL_EVALUATION (REPORT_READY COMPLETED|REPORT_FAILED FINAL_EVALUATION)");
            }
        }
        assertThat(edgesTakenUnderAnyGuard()).filteredOn(edge -> edge.endsWith(" COMPLETED"))
                .containsExactly("FINAL_EVALUATION REPORT_READY COMPLETED");
    }

    @ParameterizedTest(name = "{0} + {1} [followUp={2} slots={3} next={4} resume={5}] -> {6}")
    @CsvSource(nullValues = "-", textBlock = """
            # from, event, followUpAllowed, slotsRemain, nextSection, resumeSection, expectedTo, expectedResume, expectedEndReason
            INIT, START, false, false, -, -, INTRODUCTION, -, -
            INIT, START, true, true, CODING, CODING, INTRODUCTION, -, -
            INIT, ABANDON, false, false, -, -, ABANDONED, -, ABANDONED

            INTRODUCTION, INTRO_ANSWERED, false, false, QUESTIONING, -, QUESTIONING, -, -
            INTRODUCTION, INTRO_ANSWERED, false, false, CODING, -, CODING, -, -
            INTRODUCTION, INTRO_ANSWERED, false, false, SYSTEM_DESIGN, -, SYSTEM_DESIGN, -, -
            INTRODUCTION, INTRO_ANSWERED, false, false, -, -, ILLEGAL, -, -
            INTRODUCTION, INTRO_ANSWERED, false, false, INTRODUCTION, -, ILLEGAL, -, -
            INTRODUCTION, TIME_UP, false, false, -, -, FINAL_EVALUATION, -, TIME_UP
            INTRODUCTION, END_REQUESTED, false, false, -, -, FINAL_EVALUATION, -, CANDIDATE_ENDED

            QUESTIONING, ANSWER_EVALUATED, true, false, -, -, FOLLOW_UP, QUESTIONING, -
            QUESTIONING, ANSWER_EVALUATED, true, true, -, -, FOLLOW_UP, QUESTIONING, -
            QUESTIONING, ANSWER_EVALUATED, false, true, -, -, QUESTIONING, -, -
            QUESTIONING, ANSWER_EVALUATED, false, false, -, -, ILLEGAL, -, -
            QUESTIONING, SECTION_COMPLETE, false, false, CODING, -, CODING, -, -
            QUESTIONING, SECTION_COMPLETE, false, false, SYSTEM_DESIGN, -, SYSTEM_DESIGN, -, -
            QUESTIONING, SECTION_COMPLETE, false, false, -, -, FINAL_EVALUATION, -, PLAN_COMPLETE
            QUESTIONING, SECTION_COMPLETE, false, false, QUESTIONING, -, ILLEGAL, -, -
            QUESTIONING, SECTION_COMPLETE, false, false, INTRODUCTION, -, ILLEGAL, -, -
            QUESTIONING, TIME_UP, true, true, CODING, QUESTIONING, FINAL_EVALUATION, -, TIME_UP
            QUESTIONING, END_REQUESTED, true, true, CODING, QUESTIONING, FINAL_EVALUATION, -, CANDIDATE_ENDED

            FOLLOW_UP, ANSWER_EVALUATED, true, false, -, QUESTIONING, FOLLOW_UP, QUESTIONING, -
            FOLLOW_UP, ANSWER_EVALUATED, true, false, -, CODING, FOLLOW_UP, CODING, -
            FOLLOW_UP, ANSWER_EVALUATED, true, false, -, SYSTEM_DESIGN, FOLLOW_UP, SYSTEM_DESIGN, -
            FOLLOW_UP, ANSWER_EVALUATED, false, true, -, QUESTIONING, ILLEGAL, -, -
            FOLLOW_UP, ANSWER_EVALUATED, true, true, -, -, ILLEGAL, -, -
            FOLLOW_UP, ANSWER_EVALUATED, true, true, -, INTRODUCTION, ILLEGAL, -, -
            FOLLOW_UP, FOLLOW_UP_DONE, false, false, -, QUESTIONING, QUESTIONING, -, -
            FOLLOW_UP, FOLLOW_UP_DONE, false, false, -, CODING, CODING, -, -
            FOLLOW_UP, FOLLOW_UP_DONE, false, false, -, SYSTEM_DESIGN, SYSTEM_DESIGN, -, -
            FOLLOW_UP, FOLLOW_UP_DONE, false, false, -, -, ILLEGAL, -, -
            FOLLOW_UP, FOLLOW_UP_DONE, false, false, -, INTRODUCTION, ILLEGAL, -, -
            FOLLOW_UP, TIME_UP, false, false, -, CODING, FINAL_EVALUATION, -, TIME_UP
            FOLLOW_UP, END_REQUESTED, false, false, -, CODING, FINAL_EVALUATION, -, CANDIDATE_ENDED

            CODING, ANSWER_EVALUATED, true, false, -, -, FOLLOW_UP, CODING, -
            CODING, ANSWER_EVALUATED, true, true, -, -, FOLLOW_UP, CODING, -
            CODING, ANSWER_EVALUATED, false, true, -, -, CODING, -, -
            CODING, ANSWER_EVALUATED, false, false, -, -, ILLEGAL, -, -
            CODING, SECTION_COMPLETE, false, false, SYSTEM_DESIGN, -, SYSTEM_DESIGN, -, -
            CODING, SECTION_COMPLETE, false, false, -, -, FINAL_EVALUATION, -, PLAN_COMPLETE
            CODING, SECTION_COMPLETE, false, false, CODING, -, ILLEGAL, -, -
            CODING, SECTION_COMPLETE, false, false, QUESTIONING, -, ILLEGAL, -, -
            CODING, TIME_UP, false, false, -, -, FINAL_EVALUATION, -, TIME_UP
            CODING, END_REQUESTED, false, false, -, -, FINAL_EVALUATION, -, CANDIDATE_ENDED

            SYSTEM_DESIGN, ANSWER_EVALUATED, true, false, -, -, FOLLOW_UP, SYSTEM_DESIGN, -
            SYSTEM_DESIGN, ANSWER_EVALUATED, true, true, -, -, FOLLOW_UP, SYSTEM_DESIGN, -
            SYSTEM_DESIGN, ANSWER_EVALUATED, false, true, -, -, ILLEGAL, -, -
            SYSTEM_DESIGN, ANSWER_EVALUATED, false, false, -, -, ILLEGAL, -, -
            SYSTEM_DESIGN, SECTION_COMPLETE, false, false, -, -, FINAL_EVALUATION, -, PLAN_COMPLETE
            SYSTEM_DESIGN, SECTION_COMPLETE, false, false, SYSTEM_DESIGN, -, ILLEGAL, -, -
            SYSTEM_DESIGN, SECTION_COMPLETE, false, false, CODING, -, ILLEGAL, -, -
            SYSTEM_DESIGN, SECTION_COMPLETE, false, false, QUESTIONING, -, ILLEGAL, -, -
            SYSTEM_DESIGN, TIME_UP, false, false, -, -, FINAL_EVALUATION, -, TIME_UP
            SYSTEM_DESIGN, END_REQUESTED, false, false, -, -, FINAL_EVALUATION, -, CANDIDATE_ENDED

            FINAL_EVALUATION, REPORT_READY, false, false, -, -, COMPLETED, -, -
            FINAL_EVALUATION, REPORT_FAILED, false, false, -, -, FINAL_EVALUATION, -, -
            FINAL_EVALUATION, REPORT_READY, true, true, CODING, CODING, COMPLETED, -, -
            """)
    void guardsPickTheTargetStateOrMakeTheEventIllegal(
            InterviewState from,
            InterviewEvent event,
            boolean followUpAllowed,
            boolean slotsRemain,
            Section nextSection,
            Section resumeSection,
            String expectedTo,
            Section expectedResume,
            EndReason expectedEndReason) {
        TransitionGuards guards = new TransitionGuards(followUpAllowed, slotsRemain, nextSection, resumeSection);

        if (expectedTo.equals("ILLEGAL")) {
            assertThatThrownBy(() -> InterviewStateMachine.transition(from, event, guards))
                    .isInstanceOfSatisfying(IllegalTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(from);
                        assertThat(e.event()).isEqualTo(event);
                    });
            return;
        }
        StateTransition taken = InterviewStateMachine.transition(from, event, guards);

        assertThat(taken.from()).isEqualTo(from);
        assertThat(taken.event()).isEqualTo(event);
        assertThat(taken.to()).isEqualTo(InterviewState.valueOf(expectedTo));
        assertThat(taken.resumeSection()).isEqualTo(expectedResume);
        assertThat(taken.endReason()).isEqualTo(expectedEndReason);
    }

    @Test
    void everyAcceptedPairHasAtLeastOneGuardCombinationThatTakesIt() {
        for (InterviewState state : InterviewState.values()) {
            for (InterviewEvent event : DOCUMENTED_EVENTS.get(state)) {
                boolean taken = allGuards().stream().anyMatch(guards -> {
                    try {
                        InterviewStateMachine.transition(state, event, guards);
                        return true;
                    } catch (IllegalTransitionException e) {
                        return false;
                    }
                });

                assertThat(taken).as(state + " + " + event + " is reachable").isTrue();
            }
        }
    }

    @Test
    void theCreationEdgeStartsInInitWithoutAFromState() {
        StateTransition creation = InterviewStateMachine.creation();

        assertThat(creation.from()).isNull();
        assertThat(creation.event()).isEqualTo(CREATE);
        assertThat(creation.to()).isEqualTo(INIT);
        assertThat(creation.resumeSection()).isNull();
        assertThat(creation.endReason()).isNull();
    }

    @Test
    void createIsNotAcceptedByAnyState() {
        for (InterviewState state : InterviewState.values()) {
            assertThat(InterviewStateMachine.accepts(state, CREATE)).isFalse();
        }
    }

    @Test
    void illegalTransitionExceptionNamesTheStateAndTheEvent() {
        assertThatThrownBy(() -> InterviewStateMachine.transition(COMPLETED, START, TransitionGuards.NONE))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("START")
                .hasMessageContaining("COMPLETED");
        assertThat(new IllegalTransitionException(null, CREATE, "no").from()).isNull();
        assertThat(new IllegalTransitionException(null, CREATE, "no")).hasMessageContaining("none");
    }

    @Test
    void anAcceptedEventWhoseGuardFailsSaysSoInTheMessage() {
        assertThatThrownBy(() -> InterviewStateMachine.transition(QUESTIONING, ANSWER_EVALUATED, TransitionGuards.NONE))
                .hasMessageContaining("guard");
        assertThatThrownBy(() -> InterviewStateMachine.transition(QUESTIONING, START, TransitionGuards.NONE))
                .hasMessageContaining("does not accept");
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> InterviewStateMachine.transition(null, START, TransitionGuards.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewStateMachine.transition(INIT, null, TransitionGuards.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewStateMachine.transition(INIT, START, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptedEventsCannotBeUsedToModifyTheMachine() {
        Set<InterviewEvent> events = InterviewStateMachine.acceptedEvents(INIT);

        assertThatThrownBy(() -> events.add(TIME_UP)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(InterviewStateMachine.acceptedEvents(INIT)).containsExactlyInAnyOrder(START, ABANDON);
    }

    @Test
    void stateTransitionRequiresAResumeSectionExactlyInFollowUp() {
        assertThatThrownBy(() -> new StateTransition(QUESTIONING, ANSWER_EVALUATED, FOLLOW_UP, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateTransition(QUESTIONING, ANSWER_EVALUATED, QUESTIONING, Section.QUESTIONING, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateTransition(QUESTIONING, ANSWER_EVALUATED, FOLLOW_UP, Section.INTRODUCTION, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateTransition(null, START, INTRODUCTION, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateTransition(null, CREATE, QUESTIONING, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StateTransition(QUESTIONING, ANSWER_EVALUATED, FOLLOW_UP, Section.QUESTIONING, null).resumeSection())
                .isEqualTo(Section.QUESTIONING);
    }

    @Test
    void guardWithersChangeExactlyOneSlot() {
        TransitionGuards base = new TransitionGuards(false, false, null, null);

        assertThat(base.withFollowUpAllowed(true)).isEqualTo(new TransitionGuards(true, false, null, null));
        assertThat(base.withSlotsRemain(true)).isEqualTo(new TransitionGuards(false, true, null, null));
        assertThat(base.withNextSection(Section.CODING)).isEqualTo(new TransitionGuards(false, false, Section.CODING, null));
        assertThat(base.withResumeSection(Section.CODING)).isEqualTo(new TransitionGuards(false, false, null, Section.CODING));
        assertThat(TransitionGuards.NONE).isEqualTo(base);
    }
}
