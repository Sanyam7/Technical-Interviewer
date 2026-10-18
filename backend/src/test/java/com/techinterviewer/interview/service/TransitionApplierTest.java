package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.engine.StateTransition;
import com.techinterviewer.interview.engine.TransitionGuards;
import com.techinterviewer.interview.repository.InterviewStateTransitionRepository;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransitionApplierTest {

    private static final Instant T0 = Fixtures.NOW;
    private static final UUID CANDIDATE = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d11");

    private final List<InterviewStateTransition> saved = new ArrayList<>();
    private TransitionApplier applier;

    @BeforeEach
    void setUp() {
        InterviewStateTransitionRepository audit = mock(InterviewStateTransitionRepository.class);
        when(audit.save(any())).thenAnswer(call -> {
            InterviewStateTransition row = call.getArgument(0);
            saved.add(row);
            return row;
        });
        applier = new TransitionApplier(audit);
    }

    private static Interview created() {
        return Interview.create(CANDIDATE, UUID.randomUUID(), Fixtures.plan(), T0);
    }

    private static Interview started() {
        Interview interview = created();
        interview.start(T0, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
        return interview;
    }

    @Test
    void theCreationRowHasNoSourceStateAndTargetsInit() {
        Interview interview = created();

        applier.recordCreation(interview, T0);

        assertThat(saved).hasSize(1);
        InterviewStateTransition row = saved.getFirst();
        assertThat(row.getInterviewId()).isEqualTo(interview.getId());
        assertThat(row.getFromState()).isNull();
        assertThat(row.getToState()).isEqualTo(InterviewState.INIT);
        assertThat(row.getEvent()).isEqualTo(InterviewEvent.CREATE);
        assertThat(row.getOccurredAt()).isEqualTo(T0);
    }

    @Test
    void anAllowedEventMovesTheInterviewAndSavesItsLogRowWithTheDetails() {
        Interview interview = started();
        interview.askQuestion(UUID.randomUUID(), T0);
        Instant later = T0.plusSeconds(100);

        StateTransition edge = applier.edge(interview, InterviewEvent.END_REQUESTED, TransitionGuards.NONE);
        InterviewStateTransition row = applier.apply(interview, edge, later, Map.of("why", "test"));

        assertThat(interview.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(interview.getEndReason()).isEqualTo(EndReason.CANDIDATE_ENDED);
        assertThat(interview.getEndedAt()).isEqualTo(later);
        assertThat(interview.getCurrentQuestionId()).isNull();
        assertThat(saved).containsExactly(row);
        assertThat(row.getFromState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(row.getToState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(row.getEvent()).isEqualTo(InterviewEvent.END_REQUESTED);
        assertThat(row.getDetails()).containsEntry("why", "test");
        assertThat(row.getOccurredAt()).isEqualTo(later);
    }

    @Test
    void theConvenienceOverloadAsksTheMachineAndApplies() {
        Interview interview = created();

        applier.apply(interview, InterviewEvent.ABANDON, TransitionGuards.NONE, T0);

        assertThat(interview.getState()).isEqualTo(InterviewState.ABANDONED);
        assertThat(interview.getEndReason()).isEqualTo(EndReason.ABANDONED);
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getEvent()).isEqualTo(InterviewEvent.ABANDON));
    }

    @ParameterizedTest
    @EnumSource(value = InterviewEvent.class, names = {"START", "ANSWER_EVALUATED", "TIME_UP", "REPORT_READY"})
    void anIllegalEventIsA409WithTheCurrentStateAndChangesNothing(InterviewEvent event) {
        Interview interview = created();
        if (event == InterviewEvent.START) {
            interview.start(T0, 60);
            interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
        }
        InterviewState before = interview.getState();

        assertThatThrownBy(() -> applier.apply(interview, event, TransitionGuards.NONE, T0))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
                    assertThat(ex.properties()).containsEntry("currentState", before.name());
                    assertThat(ex.getMessage()).contains(event.name()).contains(before.name());
                });
        assertThat(interview.getState()).isEqualTo(before);
        assertThat(saved).isEmpty();
    }

    @Test
    void aTerminalStateAcceptsNothing() {
        Interview interview = created();
        applier.apply(interview, InterviewEvent.ABANDON, TransitionGuards.NONE, T0);
        saved.clear();

        for (InterviewEvent event : InterviewEvent.values()) {
            assertThatThrownBy(() -> applier.edge(interview, event, TransitionGuards.NONE))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.properties()).containsEntry("currentState", "ABANDONED"));
        }
    }

    @Test
    void edgeAloneChangesNothing() {
        Interview interview = created();

        applier.edge(interview, InterviewEvent.ABANDON, TransitionGuards.NONE);

        assertThat(interview.getState()).isEqualTo(InterviewState.INIT);
        assertThat(saved).isEmpty();
    }

    @Test
    void aQuestionThatWasDecidedButNotProducedIsForgottenWhenTheQuestionPhaseEnds() {
        Interview interview = started();
        interview.replaceMemory(InterviewMemory.empty().withPendingStep(PendingStep.nextTopic(Section.QUESTIONING, "java/concurrency", 3)), T0);

        applier.apply(interview, InterviewEvent.TIME_UP, TransitionGuards.NONE, T0.plusSeconds(1));

        assertThat(interview.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(interview.getMemory().pendingStep()).isNull();
    }

    @Test
    void aPendingQuestionIsKeptWhileTheInterviewKeepsAskingQuestions() {
        Interview interview = started();
        PendingStep step = PendingStep.nextTopic(Section.QUESTIONING, "java/concurrency", 3);
        interview.replaceMemory(InterviewMemory.empty().withPendingStep(step), T0);

        applier.apply(interview, InterviewEvent.INTRO_ANSWERED, TransitionGuards.NONE.withNextSection(Section.QUESTIONING), T0.plusSeconds(1));

        assertThat(interview.getState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(interview.getMemory().pendingStep()).isEqualTo(step);
    }

    @Test
    void anEdgeThatLeavesAnotherStateThanTheCurrentOneIsRefused() {
        Interview interview = created();
        StateTransition stale = applier.edge(interview, InterviewEvent.ABANDON, TransitionGuards.NONE);
        applier.apply(interview, stale, T0, null);

        assertThatThrownBy(() -> applier.apply(interview, stale, T0, null)).isInstanceOf(IllegalStateException.class);
    }
}
