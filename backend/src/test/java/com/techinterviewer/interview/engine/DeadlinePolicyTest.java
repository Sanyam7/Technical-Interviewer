package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeadlinePolicyTest {

    private static final Instant START = Instant.parse("2026-10-06T10:00:00Z");
    private static final Instant DEADLINE = START.plus(Duration.ofMinutes(60));
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final DifficultyBand BAND = new DifficultyBand(3, 2, 4);
    private static final InterviewPlan QUESTIONING_ONLY = new InterviewPlan(1, List.of(
            new SectionPlan(Section.INTRODUCTION, 4, 1), new SectionPlan(Section.QUESTIONING, 24, 6)), BAND, 2);
    private static final InterviewPlan FULL_LOOP = new InterviewPlan(1, List.of(
            new SectionPlan(Section.INTRODUCTION, 4, 1),
            new SectionPlan(Section.QUESTIONING, 24, 6),
            new SectionPlan(Section.CODING, 16, 1),
            new SectionPlan(Section.SYSTEM_DESIGN, 14, 1)), BAND, 2);

    private static InterviewMemory memoryWithSectionStart(Instant startedAt) {
        return new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(),
                Map.of(Section.QUESTIONING, new SectionProgress(1, startedAt), Section.SYSTEM_DESIGN, new SectionProgress(0, START.plus(
                        Duration.ofMinutes(50)))), null);
    }

    @Test
    void deadlineIsTheStartPlusTheConfiguredMinutes() {
        assertThat(DeadlinePolicy.deadline(START, 45)).isEqualTo(START.plus(Duration.ofMinutes(45)));
        assertThat(DeadlinePolicy.deadline(START, 1)).isEqualTo(START.plusSeconds(60));
        assertThatThrownBy(() -> DeadlinePolicy.deadline(START, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeadlinePolicy.deadline(null, 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInterviewIsNotExpiredAtTheDeadlineItselfButOneNanosecondAfter() {
        assertThat(DeadlinePolicy.isExpired(DEADLINE.minusNanos(1), DEADLINE)).isFalse();
        assertThat(DeadlinePolicy.isExpired(DEADLINE, DEADLINE)).isFalse();
        assertThat(DeadlinePolicy.isExpired(DEADLINE.plusNanos(1), DEADLINE)).isTrue();
    }

    @Test
    void statusIsRunningUpToTheDeadlineWithinGraceUpToGraceInclusiveAndExpiredAfter() {
        assertThat(DeadlinePolicy.status(START, DEADLINE, GRACE)).isEqualTo(DeadlineStatus.RUNNING);
        assertThat(DeadlinePolicy.status(DEADLINE, DEADLINE, GRACE)).isEqualTo(DeadlineStatus.RUNNING);
        assertThat(DeadlinePolicy.status(DEADLINE.plusNanos(1), DEADLINE, GRACE)).isEqualTo(DeadlineStatus.WITHIN_GRACE);
        assertThat(DeadlinePolicy.status(DEADLINE.plusSeconds(29), DEADLINE, GRACE)).isEqualTo(DeadlineStatus.WITHIN_GRACE);
        assertThat(DeadlinePolicy.status(DEADLINE.plus(GRACE), DEADLINE, GRACE)).isEqualTo(DeadlineStatus.WITHIN_GRACE);
        assertThat(DeadlinePolicy.status(DEADLINE.plus(GRACE).plusNanos(1), DEADLINE, GRACE)).isEqualTo(DeadlineStatus.EXPIRED);
        assertThat(DeadlinePolicy.status(DEADLINE.plusSeconds(3600), DEADLINE, GRACE)).isEqualTo(DeadlineStatus.EXPIRED);
    }

    @Test
    void aZeroGraceWindowGoesStraightFromRunningToExpired() {
        assertThat(DeadlinePolicy.status(DEADLINE, DEADLINE, Duration.ZERO)).isEqualTo(DeadlineStatus.RUNNING);
        assertThat(DeadlinePolicy.status(DEADLINE.plusNanos(1), DEADLINE, Duration.ZERO)).isEqualTo(DeadlineStatus.EXPIRED);
    }

    @Test
    void aNegativeOrMissingGraceIsRejected() {
        assertThatThrownBy(() -> DeadlinePolicy.status(START, DEADLINE, Duration.ofSeconds(-1)))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> DeadlinePolicy.status(START, DEADLINE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remainingTimeCountsDownAndNeverGoesNegative() {
        assertThat(DeadlinePolicy.remaining(START, DEADLINE)).isEqualTo(Duration.ofMinutes(60));
        assertThat(DeadlinePolicy.remaining(DEADLINE.minusSeconds(1), DEADLINE)).isEqualTo(Duration.ofSeconds(1));
        assertThat(DeadlinePolicy.remaining(DEADLINE, DEADLINE)).isEqualTo(Duration.ZERO);
        assertThat(DeadlinePolicy.remaining(DEADLINE.plusSeconds(5), DEADLINE)).isEqualTo(Duration.ZERO);
    }

    @Test
    void aSectionStartsANewQuestionOnlyWhileTheQuestionCanStillBeAnswered() {
        InterviewMemory memory = memoryWithSectionStart(START);
        Instant twoMinutesLeft = START.plus(Duration.ofMinutes(22));

        assertThat(DeadlinePolicy.canStartQuestion(memory, QUESTIONING_ONLY, Section.QUESTIONING, twoMinutesLeft, DEADLINE)).isTrue();
        assertThat(DeadlinePolicy.canStartQuestion(memory, QUESTIONING_ONLY, Section.QUESTIONING, twoMinutesLeft.plusNanos(1), DEADLINE))
                .isFalse();
        assertThat(DeadlinePolicy.canStartQuestion(memory, QUESTIONING_ONLY, Section.QUESTIONING, START, DEADLINE)).isTrue();
    }

    @Test
    void aTechnicalQuestionNeedsTwoMinutesAndAProblemEightUnlessThePlanGaveTheSectionLess() {
        assertThat(DeadlinePolicy.timeToStart(FULL_LOOP, Section.QUESTIONING)).isEqualTo(Duration.ofMinutes(2));
        assertThat(DeadlinePolicy.timeToStart(FULL_LOOP, Section.CODING)).isEqualTo(Duration.ofMinutes(8));
        assertThat(DeadlinePolicy.timeToStart(FULL_LOOP, Section.SYSTEM_DESIGN)).isEqualTo(Duration.ofMinutes(8));
        InterviewPlan shortCoding = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 2, 1), new SectionPlan(Section.CODING, 4, 1)), BAND, 2);
        assertThat(DeadlinePolicy.timeToStart(shortCoding, Section.CODING)).as("the planned budget").isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void timeLeftInASectionNotYetStartedIsItsWholeBudget() {
        assertThat(DeadlinePolicy.timeLeftInSection(InterviewMemory.empty(), QUESTIONING_ONLY, Section.QUESTIONING, START, DEADLINE))
                .isEqualTo(Duration.ofMinutes(24));
    }

    @Test
    void timeLeftInAStartedSectionIsWhatRemainsOfItsBudget() {
        InterviewMemory memory = memoryWithSectionStart(START);

        assertThat(DeadlinePolicy.timeLeftInSection(memory, QUESTIONING_ONLY, Section.QUESTIONING, START.plus(Duration.ofMinutes(10)), DEADLINE))
                .isEqualTo(Duration.ofMinutes(14));
        assertThat(DeadlinePolicy.timeLeftInSection(memory, QUESTIONING_ONLY, Section.QUESTIONING, START.plus(Duration.ofMinutes(24)), DEADLINE))
                .isEqualTo(Duration.ZERO);
        assertThat(DeadlinePolicy.timeLeftInSection(memory, QUESTIONING_ONLY, Section.QUESTIONING, START.plus(Duration.ofMinutes(30)), DEADLINE))
                .as("never negative").isEqualTo(Duration.ZERO);
    }

    @Test
    void timeLeftInASectionIsCappedByTheInterviewDeadline() {
        InterviewMemory memory = memoryWithSectionStart(DEADLINE.minus(Duration.ofMinutes(5)));
        Instant twoBefore = DEADLINE.minus(Duration.ofMinutes(2));

        assertThat(DeadlinePolicy.timeLeftInSection(memory, QUESTIONING_ONLY, Section.QUESTIONING, twoBefore, DEADLINE))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(DeadlinePolicy.timeLeftInSection(InterviewMemory.empty(), QUESTIONING_ONLY, Section.QUESTIONING, twoBefore, DEADLINE))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(DeadlinePolicy.timeLeftInSection(memory, QUESTIONING_ONLY, Section.QUESTIONING, DEADLINE.plusSeconds(10), DEADLINE))
                .as("past the deadline").isEqualTo(Duration.ZERO);
    }

    @Test
    void onScheduleASectionHasItsWholeBudgetButItNeverTakesTheTimePlannedForTheSectionsAfterIt() {
        // Full loop: introduction 4, questioning 24, coding 16, design 14; coding and design keep 30 minutes for themselves.
        InterviewMemory onTime = memoryWithSectionStart(START.plus(Duration.ofMinutes(4)));
        assertThat(DeadlinePolicy.timeLeftInSection(onTime, FULL_LOOP, Section.QUESTIONING, START.plus(Duration.ofMinutes(10)), DEADLINE))
                .as("own budget: 28 - 10").isEqualTo(Duration.ofMinutes(18));

        InterviewMemory late = memoryWithSectionStart(START.plus(Duration.ofMinutes(10)));
        assertThat(DeadlinePolicy.timeLeftInSection(late, FULL_LOOP, Section.QUESTIONING, START.plus(Duration.ofMinutes(20)), DEADLINE))
                .as("the introduction overran by 6 minutes: 60 - 20 - 30 = 10, not 34 - 20 = 14").isEqualTo(Duration.ofMinutes(10));
        assertThat(DeadlinePolicy.timeLeftInSection(late, FULL_LOOP, Section.SYSTEM_DESIGN, START.plus(Duration.ofMinutes(52)), DEADLINE))
                .as("the last section reserves nothing: only the deadline caps its 12 minutes").isEqualTo(Duration.ofMinutes(8));
    }

    @Test
    void theClosingWindowIsTheLastTenthOfThePlanButAtLeastFiveMinutes() {
        Duration window = DeadlinePolicy.closingWindow(FULL_LOOP);
        assertThat(window).as("58 planned minutes").isEqualTo(Duration.ofSeconds(348));
        assertThat(DeadlinePolicy.isClosing(FULL_LOOP, DEADLINE.minus(window), DEADLINE)).isTrue();
        assertThat(DeadlinePolicy.isClosing(FULL_LOOP, DEADLINE.minus(window).minusNanos(1), DEADLINE)).isFalse();
        assertThat(DeadlinePolicy.closingWindow(QUESTIONING_ONLY)).as("28 planned minutes").isEqualTo(Duration.ofMinutes(5));
    }
}
