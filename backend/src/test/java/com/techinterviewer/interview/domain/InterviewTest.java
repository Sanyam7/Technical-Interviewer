package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The lifecycle and the guards of the Interview entity, without a database. */
class InterviewTest {

    private static final Instant T0 = Fixtures.NOW;
    private static final Instant T1 = T0.plusSeconds(10);
    private static final Instant T2 = T0.plusSeconds(20);
    private static final UUID CANDIDATE = UuidV7.generate();
    private static final UUID CONFIGURATION = UuidV7.generate();

    private static Interview created() {
        return Interview.create(CANDIDATE, CONFIGURATION, Fixtures.plan(), T0);
    }

    private static Interview started() {
        Interview interview = created();
        interview.start(T0, 60);
        return interview;
    }

    private static Interview inQuestioning() {
        Interview interview = started();
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
        interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, T0);
        return interview;
    }

    private static Interview inFinalEvaluation() {
        Interview interview = inQuestioning();
        interview.applyTransition(InterviewState.QUESTIONING, InterviewState.FINAL_EVALUATION, InterviewEvent.SECTION_COMPLETE, T1);
        return interview;
    }

    // ---- creation ------------------------------------------------------------------------------------------------

    @Test
    void aNewInterviewIsInInitWithEmptyMemoryAndNothingElseSet() {
        Interview interview = created();

        assertThat(interview.getState()).isEqualTo(InterviewState.INIT);
        assertThat(interview.getCandidateId()).isEqualTo(CANDIDATE);
        assertThat(interview.getConfigurationId()).isEqualTo(CONFIGURATION);
        assertThat(interview.getPlan()).isEqualTo(Fixtures.plan());
        assertThat(interview.getMemory()).isEqualTo(InterviewMemory.empty());
        assertThat(interview.getCreatedAt()).isEqualTo(T0);
        assertThat(interview.getUpdatedAt()).isEqualTo(T0);
        assertThat(interview.getVersion()).isZero();
        assertThat(interview.getStartedAt()).isNull();
        assertThat(interview.getDeadlineAt()).isNull();
        assertThat(interview.getEndedAt()).isNull();
        assertThat(interview.getEndReason()).isNull();
        assertThat(interview.getResumeState()).isNull();
        assertThat(interview.getCurrentQuestionId()).isNull();
        assertThat(interview.isTerminal()).isFalse();
        assertThat(UuidV7.isV7(interview.getId())).isTrue();
    }

    @Test
    void creationRejectsMissingValues() {
        InterviewPlan plan = Fixtures.plan();

        assertThatIllegalArgumentException().isThrownBy(() -> Interview.create(null, CONFIGURATION, plan, T0)).withMessageContaining("candidateId");
        assertThatIllegalArgumentException().isThrownBy(() -> Interview.create(CANDIDATE, null, plan, T0)).withMessageContaining("configurationId");
        assertThatIllegalArgumentException().isThrownBy(() -> Interview.create(CANDIDATE, CONFIGURATION, null, T0)).withMessageContaining("plan");
        assertThatIllegalArgumentException().isThrownBy(() -> Interview.create(CANDIDATE, CONFIGURATION, plan, null)).withMessageContaining("now");
    }

    // ---- start ---------------------------------------------------------------------------------------------------

    @Test
    void startSetsTheStartTimeAndTheDeadlineFromTheDuration() {
        Interview interview = created();

        interview.start(T1, 45);

        assertThat(interview.getStartedAt()).isEqualTo(T1);
        assertThat(interview.getDeadlineAt()).isEqualTo(T1.plus(Duration.ofMinutes(45)));
        assertThat(interview.getUpdatedAt()).isEqualTo(T1);
        assertThat(interview.getState()).as("start() only starts the clock; the state moves with applyTransition").isEqualTo(InterviewState.INIT);
    }

    @Test
    void startHappensOnlyOnce() {
        Interview interview = started();

        assertThatIllegalStateException().isThrownBy(() -> interview.start(T1, 30)).withMessageContaining("started already");

        assertThat(interview.getStartedAt()).isEqualTo(T0);
        assertThat(interview.getDeadlineAt()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
    }

    @Test
    void anAbandonedInterviewCannotBeStarted() {
        Interview interview = created();
        interview.applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T1);

        assertThatIllegalStateException().isThrownBy(() -> interview.start(T2, 30));
        assertThat(interview.getStartedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "-60"})
    void startRejectsADurationBelowOneMinute(int minutes) {
        Interview interview = created();

        assertThatIllegalArgumentException().isThrownBy(() -> interview.start(T0, minutes)).withMessageContaining("durationMinutes");
        assertThat(interview.getStartedAt()).isNull();
    }

    // ---- applyTransition: guards ---------------------------------------------------------------------------------

    @Test
    void aTransitionFromAStateTheInterviewIsNotInIsRefusedAndChangesNothing() {
        Interview interview = inQuestioning();

        assertThatIllegalStateException()
                .isThrownBy(() -> interview.applyTransition(InterviewState.CODING, InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP, T2))
                .withMessageContaining("QUESTIONING")
                .withMessageContaining("CODING");

        assertThat(interview.getState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(interview.getEndedAt()).isNull();
        assertThat(interview.getUpdatedAt()).isEqualTo(T0);
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"COMPLETED", "ABANDONED"})
    void aTerminalInterviewNeverMovesAgain(InterviewState terminal) {
        Interview interview = terminal == InterviewState.COMPLETED ? inFinalEvaluation() : created();
        if (terminal == InterviewState.COMPLETED) {
            interview.applyTransition(InterviewState.FINAL_EVALUATION, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, T2);
        } else {
            interview.applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T1);
        }

        for (InterviewState target : InterviewState.values()) {
            assertThatIllegalStateException()
                    .as("%s to %s", terminal, target)
                    .isThrownBy(() -> interview.applyTransition(terminal, target, InterviewEvent.END_REQUESTED, T2))
                    .withMessageContaining("can no longer change");
        }
        assertThat(interview.getState()).isEqualTo(terminal);
        assertThat(interview.isTerminal()).isTrue();
    }

    @Test
    void anInterviewNeverReturnsToInitAndCreateIsNotATransition() {
        Interview interview = inQuestioning();

        assertThatIllegalStateException()
                .isThrownBy(() -> interview.applyTransition(InterviewState.QUESTIONING, InterviewState.INIT, InterviewEvent.START, T1));
        assertThatIllegalStateException()
                .isThrownBy(() -> interview.applyTransition(InterviewState.QUESTIONING, InterviewState.CODING, InterviewEvent.CREATE, T1));
        assertThat(interview.getState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void anInterviewThatWasNeverStartedCanOnlyBeAbandoned() {
        Interview interview = created();

        assertThatIllegalStateException()
                .isThrownBy(() -> interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T1))
                .withMessageContaining("not been started");
        assertThat(interview.getState()).isEqualTo(InterviewState.INIT);

        InterviewStateTransition row = interview.applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T1);

        assertThat(interview.getState()).isEqualTo(InterviewState.ABANDONED);
        assertThat(row.getFromState()).isEqualTo(InterviewState.INIT);
        assertThat(row.getToState()).isEqualTo(InterviewState.ABANDONED);
    }

    @Test
    void completingRequiresThatTheInterviewEndedFirst() {
        Interview interview = inQuestioning();

        assertThatIllegalStateException()
                .isThrownBy(() -> interview.applyTransition(InterviewState.QUESTIONING, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, T1))
                .withMessageContaining("before it ended");
        assertThat(interview.getState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void transitionArgumentsAreRequired() {
        Interview interview = inQuestioning();

        assertThatIllegalArgumentException().isThrownBy(() -> interview.applyTransition(null, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, T1));
        assertThatIllegalArgumentException().isThrownBy(() -> interview.applyTransition(InterviewState.QUESTIONING, null, InterviewEvent.SECTION_COMPLETE, T1));
        assertThatIllegalArgumentException().isThrownBy(() -> interview.applyTransition(InterviewState.QUESTIONING, InterviewState.CODING, null, T1));
        assertThatIllegalArgumentException().isThrownBy(() -> interview.applyTransition(InterviewState.QUESTIONING, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, null));
    }

    // ---- applyTransition: effects --------------------------------------------------------------------------------

    @Test
    void aTransitionChangesTheStateAndReturnsTheAuditRowOfTheMove() {
        Interview interview = started();

        InterviewStateTransition row = interview.applyTransition(
                InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, Map.of("by", "candidate"), T1);

        assertThat(interview.getState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(interview.getUpdatedAt()).isEqualTo(T1);
        assertThat(row.getInterviewId()).isEqualTo(interview.getId());
        assertThat(row.getFromState()).isEqualTo(InterviewState.INIT);
        assertThat(row.getToState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(row.getEvent()).isEqualTo(InterviewEvent.START);
        assertThat(row.getDetails()).containsExactly(Map.entry("by", "candidate"));
        assertThat(row.getOccurredAt()).isEqualTo(T1);
    }

    @Test
    void aTransitionWithoutDetailsHasNone() {
        Interview interview = started();

        InterviewStateTransition row = interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T1);

        assertThat(row.getDetails()).isNull();
    }

    @Test
    void aTransitionResolvesTheCurrentQuestion() {
        Interview interview = inQuestioning();
        interview.askQuestion(UuidV7.generate(), T1);

        interview.applyTransition(InterviewState.QUESTIONING, InterviewState.QUESTIONING, InterviewEvent.ANSWER_EVALUATED, T2);

        assertThat(interview.getCurrentQuestionId()).isNull();
    }

    @ParameterizedTest(name = "{0} while in QUESTIONING ends the interview with {1}")
    @CsvSource({
        "TIME_UP,          TIME_UP",
        "END_REQUESTED,    CANDIDATE_ENDED",
        "SECTION_COMPLETE, PLAN_COMPLETE",
        "ANSWER_EVALUATED, PLAN_COMPLETE",
        "FOLLOW_UP_DONE,   PLAN_COMPLETE"
    })
    void enteringFinalEvaluationRecordsWhenAndWhyTheInterviewEnded(InterviewEvent event, EndReason reason) {
        Interview interview = inQuestioning();

        interview.applyTransition(InterviewState.QUESTIONING, InterviewState.FINAL_EVALUATION, event, T2);

        assertThat(interview.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(interview.getEndedAt()).isEqualTo(T2);
        assertThat(interview.getEndReason()).isEqualTo(reason);
    }

    @Test
    void abandoningAStartedInterviewRecordsTheEndAsAbandoned() {
        Interview interview = inQuestioning();

        interview.applyTransition(InterviewState.QUESTIONING, InterviewState.ABANDONED, InterviewEvent.ABANDON, T2);

        assertThat(interview.getEndedAt()).isEqualTo(T2);
        assertThat(interview.getEndReason()).isEqualTo(EndReason.ABANDONED);
    }

    @Test
    void aFailedReportKeepsTheOriginalEndAndAnotherEventDoesNotRewriteIt() {
        Interview interview = inFinalEvaluation();

        interview.applyTransition(InterviewState.FINAL_EVALUATION, InterviewState.FINAL_EVALUATION, InterviewEvent.REPORT_FAILED, T2);

        assertThat(interview.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(interview.getEndedAt()).isEqualTo(T1);
        assertThat(interview.getEndReason()).isEqualTo(EndReason.PLAN_COMPLETE);
    }

    @Test
    void completingKeepsTheEndAndReason() {
        Interview interview = inFinalEvaluation();

        interview.applyTransition(InterviewState.FINAL_EVALUATION, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, T2);

        assertThat(interview.getState()).isEqualTo(InterviewState.COMPLETED);
        assertThat(interview.getEndedAt()).isEqualTo(T1);
        assertThat(interview.getEndReason()).isEqualTo(EndReason.PLAN_COMPLETE);
    }

    @Test
    void movingBetweenAnsweringStatesDoesNotEndTheInterview() {
        Interview interview = inQuestioning();

        interview.applyTransition(InterviewState.QUESTIONING, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, T1);

        assertThat(interview.getEndedAt()).isNull();
        assertThat(interview.getEndReason()).isNull();
    }

    // ---- follow-up and resume state ------------------------------------------------------------------------------

    @ParameterizedTest(name = "a follow-up from {0} returns to {0}")
    @EnumSource(value = InterviewState.class, names = {"QUESTIONING", "CODING", "SYSTEM_DESIGN"})
    void enteringFollowUpRemembersTheSectionToReturnTo(InterviewState section) {
        Interview interview = interviewIn(section);

        interview.applyTransition(section, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T2);

        assertThat(interview.getState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(interview.getResumeState()).isEqualTo(section);
    }

    @ParameterizedTest(name = "a follow-up cannot start from {0}")
    @EnumSource(value = InterviewState.class, names = {"INTRODUCTION", "FINAL_EVALUATION"})
    void aFollowUpCannotStartFromAStateWithoutASectionToReturnTo(InterviewState from) {
        Interview interview = interviewIn(from);

        assertThatIllegalStateException()
                .isThrownBy(() -> interview.applyTransition(from, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T2))
                .withMessageContaining("follow-up");
        assertThat(interview.getState()).isEqualTo(from);
        assertThat(interview.getResumeState()).isNull();
    }

    @Test
    void aDeeperFollowUpKeepsTheOriginalSectionToReturnTo() {
        Interview interview = interviewIn(InterviewState.CODING);
        interview.applyTransition(InterviewState.CODING, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T1);

        interview.applyTransition(InterviewState.FOLLOW_UP, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T2);

        assertThat(interview.getResumeState()).isEqualTo(InterviewState.CODING);
    }

    @Test
    void leavingFollowUpForgetsTheSectionToReturnTo() {
        Interview back = interviewIn(InterviewState.QUESTIONING);
        back.applyTransition(InterviewState.QUESTIONING, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T1);
        back.applyTransition(InterviewState.FOLLOW_UP, InterviewState.QUESTIONING, InterviewEvent.FOLLOW_UP_DONE, T2);
        assertThat(back.getResumeState()).isNull();

        Interview out = interviewIn(InterviewState.QUESTIONING);
        out.applyTransition(InterviewState.QUESTIONING, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T1);
        out.applyTransition(InterviewState.FOLLOW_UP, InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP, T2);
        assertThat(out.getResumeState()).isNull();
        assertThat(out.getEndReason()).isEqualTo(EndReason.TIME_UP);
    }

    private static Interview interviewIn(InterviewState state) {
        Interview interview = inQuestioning();
        switch (state) {
            case QUESTIONING -> { }
            case INTRODUCTION -> interview = introduction();
            case CODING -> interview.applyTransition(InterviewState.QUESTIONING, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, T1);
            case SYSTEM_DESIGN -> {
                interview.applyTransition(InterviewState.QUESTIONING, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, T1);
                interview.applyTransition(InterviewState.CODING, InterviewState.SYSTEM_DESIGN, InterviewEvent.SECTION_COMPLETE, T1);
            }
            case FINAL_EVALUATION -> interview = inFinalEvaluation();
            default -> throw new IllegalArgumentException(state.name());
        }
        return interview;
    }

    private static Interview introduction() {
        Interview interview = started();
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
        return interview;
    }

    // ---- memory --------------------------------------------------------------------------------------------------

    @Test
    void memoryIsReplacedAsAWholeValue() {
        Interview interview = inQuestioning();

        interview.replaceMemory(Fixtures.memory(), T2);

        assertThat(interview.getMemory()).isEqualTo(Fixtures.memory());
        assertThat(interview.getUpdatedAt()).isEqualTo(T2);

        interview.replaceMemory(InterviewMemory.empty(), T2.plusSeconds(1));

        assertThat(interview.getMemory()).isEqualTo(InterviewMemory.empty());
    }

    @Test
    void memoryRequiresAValueAndATime() {
        Interview interview = inQuestioning();

        assertThatIllegalArgumentException().isThrownBy(() -> interview.replaceMemory(null, T1)).withMessageContaining("memory");
        assertThatIllegalArgumentException().isThrownBy(() -> interview.replaceMemory(InterviewMemory.empty(), null)).withMessageContaining("now");
    }

    @Test
    void aTerminalInterviewKeepsItsMemory() {
        Interview interview = created();
        interview.applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T1);

        assertThatIllegalStateException().isThrownBy(() -> interview.replaceMemory(Fixtures.memory(), T2));
        assertThat(interview.getMemory()).isEqualTo(InterviewMemory.empty());
    }

    // ---- current question ----------------------------------------------------------------------------------------

    @Test
    void aQuestionBecomesCurrentAndCanBeCleared() {
        Interview interview = inQuestioning();
        UUID question = UuidV7.generate();

        interview.askQuestion(question, T1);

        assertThat(interview.getCurrentQuestionId()).isEqualTo(question);
        assertThat(interview.getUpdatedAt()).isEqualTo(T1);

        interview.clearCurrentQuestion(T2);

        assertThat(interview.getCurrentQuestionId()).isNull();
        assertThat(interview.getUpdatedAt()).isEqualTo(T2);
    }

    @ParameterizedTest(name = "no question can be asked in {0}")
    @EnumSource(value = InterviewState.class, names = {"INIT", "FINAL_EVALUATION"})
    void aQuestionCanOnlyBeAskedWhileTheInterviewAsksQuestions(InterviewState state) {
        Interview interview = state == InterviewState.INIT ? created() : inFinalEvaluation();

        assertThatIllegalStateException().isThrownBy(() -> interview.askQuestion(UuidV7.generate(), T2)).withMessageContaining(state.name());
        assertThat(interview.getCurrentQuestionId()).isNull();
    }

    @Test
    void aTerminalInterviewHasNoCurrentQuestionToClear() {
        Interview interview = created();
        interview.applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T1);

        assertThatIllegalStateException().isThrownBy(() -> interview.clearCurrentQuestion(T2));
        assertThatIllegalStateException().isThrownBy(() -> interview.askQuestion(UuidV7.generate(), T2));
    }

    @Test
    void currentQuestionArgumentsAreRequired() {
        Interview interview = inQuestioning();

        assertThatIllegalArgumentException().isThrownBy(() -> interview.askQuestion(null, T1)).withMessageContaining("questionId");
        assertThatIllegalArgumentException().isThrownBy(() -> interview.askQuestion(UuidV7.generate(), null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> interview.clearCurrentQuestion(null)).withMessageContaining("now");
    }
}
