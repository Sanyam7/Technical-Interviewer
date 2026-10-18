package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.EngineFixtures.CONCURRENCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnDecisionTest {

    private static final PendingStep STEP = PendingStep.nextTopic(Section.QUESTIONING, CONCURRENCY, 3);
    private static final StateTransition STAY = new StateTransition(
            InterviewState.QUESTIONING, InterviewEvent.ANSWER_EVALUATED, InterviewState.QUESTIONING, null, null);
    private static final StateTransition DONE = new StateTransition(
            InterviewState.FOLLOW_UP, InterviewEvent.FOLLOW_UP_DONE, InterviewState.QUESTIONING, null, null);
    private static final StateTransition TO_CODING = new StateTransition(
            InterviewState.QUESTIONING, InterviewEvent.SECTION_COMPLETE, InterviewState.CODING, null, null);
    private static final StateTransition FINISH = new StateTransition(
            InterviewState.QUESTIONING, InterviewEvent.SECTION_COMPLETE, InterviewState.FINAL_EVALUATION, null, EndReason.PLAN_COMPLETE);

    @Test
    void aContinuingDecisionExposesTheLastTransitionAndTheStep() {
        TurnDecision decision = new TurnDecision(List.of(STAY), STEP, InterviewMemory.empty().withPendingStep(STEP));

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.resumeSection()).isNull();
        assertThat(decision.endReason()).isNull();
        assertThat(decision.interviewComplete()).isFalse();
        assertThat(decision.pendingStep()).isEqualTo(STEP);
    }

    @Test
    void aFinishingDecisionHasNoPendingStep() {
        TurnDecision decision = new TurnDecision(List.of(FINISH), null, InterviewMemory.empty());

        assertThat(decision.interviewComplete()).isTrue();
        assertThat(decision.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FINAL_EVALUATION);
    }

    @Test
    void severalTransitionsMustFormAChainAndTheLastOneCounts() {
        StateTransition followUp = new StateTransition(
                InterviewState.QUESTIONING, InterviewEvent.ANSWER_EVALUATED, InterviewState.FOLLOW_UP, Section.QUESTIONING, null);
        TurnDecision decision = new TurnDecision(List.of(DONE, TO_CODING), STEP, InterviewMemory.empty().withPendingStep(STEP));

        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.CODING);
        assertThatThrownBy(() -> new TurnDecision(List.of(STAY, DONE), STEP, InterviewMemory.empty().withPendingStep(STEP)))
                .hasMessageContaining("chain");
        assertThat(followUp.resumeSection()).isEqualTo(Section.QUESTIONING);
    }

    @Test
    void aFollowUpDecisionExposesTheResumeSection() {
        StateTransition followUp = new StateTransition(
                InterviewState.QUESTIONING, InterviewEvent.ANSWER_EVALUATED, InterviewState.FOLLOW_UP, Section.QUESTIONING, null);
        PendingStep step = PendingStep.followUp(Section.QUESTIONING, CONCURRENCY, 3, EngineFixtures.question(2),
                FollowUpMode.DEEPEN, null, null);

        TurnDecision decision = new TurnDecision(List.of(followUp), step, InterviewMemory.empty().withPendingStep(step));

        assertThat(decision.resumeSection()).isEqualTo(Section.QUESTIONING);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FOLLOW_UP);
    }

    @Test
    void invariantsAreEnforced() {
        assertThatThrownBy(() -> new TurnDecision(List.of(), STEP, InterviewMemory.empty().withPendingStep(STEP)))
                .hasMessageContaining("at least one transition");
        assertThatThrownBy(() -> new TurnDecision(List.of(STAY), STEP, InterviewMemory.empty()))
                .hasMessageContaining("memory must hold the pending step");
        assertThatThrownBy(() -> new TurnDecision(List.of(STAY), null, InterviewMemory.empty().withPendingStep(STEP)))
                .hasMessageContaining("memory must hold the pending step");
        assertThatThrownBy(() -> new TurnDecision(List.of(STAY), null, InterviewMemory.empty()))
                .hasMessageContaining("exactly when the interview continues");
        assertThatThrownBy(() -> new TurnDecision(List.of(FINISH), STEP, InterviewMemory.empty().withPendingStep(STEP)))
                .hasMessageContaining("exactly when the interview continues");
        assertThatThrownBy(() -> new TurnDecision(List.of(STAY), STEP, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
