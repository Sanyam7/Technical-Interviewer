package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestAppProperties;
import com.techinterviewer.TestClock;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.engine.DeadlineStatus;
import com.techinterviewer.persistence.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The pure part of {@link DeadlineService}: where an interview stands against its deadline (the move itself is tested with the database). */
class DeadlineStatusTest {

    private static final Instant START = Fixtures.NOW;
    private static final Instant DEADLINE = START.plus(Duration.ofMinutes(60));

    private final TestClock clock = new TestClock(START);
    private final AppProperties properties = TestAppProperties.withJwt("x", false, "tech-interviewer", Duration.ofMinutes(15));
    private final DeadlineService deadlines = new DeadlineService(clock, properties, null, null, null, null);

    private static Interview started() {
        Interview interview = Interview.create(UUID.randomUUID(), UUID.randomUUID(), Fixtures.plan(), START);
        interview.start(START, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, START);
        return interview;
    }

    @Test
    void anInterviewThatWasNotStartedHasNoDeadlineToMiss() {
        Interview interview = Interview.create(UUID.randomUUID(), UUID.randomUUID(), Fixtures.plan(), START);
        clock.set(DEADLINE.plus(Duration.ofDays(30)));

        assertThat(deadlines.status(interview)).isEqualTo(DeadlineStatus.RUNNING);
        assertThat(deadlines.isDue(interview)).isFalse();
    }

    @Test
    void theInterviewRunsUpToAndIncludingTheDeadline() {
        Interview interview = started();

        clock.set(START);
        assertThat(deadlines.status(interview)).isEqualTo(DeadlineStatus.RUNNING);
        clock.set(DEADLINE);
        assertThat(deadlines.status(interview)).isEqualTo(DeadlineStatus.RUNNING);
        assertThat(deadlines.isDue(interview)).isFalse();
    }

    @Test
    void theGracePeriodOfThirtySecondsStillAcceptsAnswersAndIsNotDue() {
        Interview interview = started();

        clock.set(DEADLINE.plusMillis(1));
        assertThat(deadlines.status(interview)).isEqualTo(DeadlineStatus.WITHIN_GRACE);
        assertThat(deadlines.isDue(interview)).isFalse();
        clock.set(DEADLINE.plusSeconds(29));
        assertThat(deadlines.isDue(interview)).isFalse();
        clock.set(DEADLINE.plusSeconds(30));
        assertThat(deadlines.status(interview)).isEqualTo(DeadlineStatus.WITHIN_GRACE);
        assertThat(deadlines.isDue(interview)).isFalse();
    }

    @Test
    void oneMillisecondAfterTheGracePeriodTheInterviewIsDue() {
        Interview interview = started();

        clock.set(DEADLINE.plusSeconds(30).plusMillis(1));

        assertThat(deadlines.status(interview)).isEqualTo(DeadlineStatus.EXPIRED);
        assertThat(deadlines.isDue(interview)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"FINAL_EVALUATION", "COMPLETED", "ABANDONED"})
    void anInterviewThatAsksNoQuestionsIsNeverDueNoMatterHowLateItIs(InterviewState state) {
        Interview interview = Interview.create(UUID.randomUUID(), UUID.randomUUID(), Fixtures.plan(), START);
        interview.start(START, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, START);
        interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, START);
        if (state != InterviewState.FINAL_EVALUATION) {
            interview.applyTransition(InterviewState.FINAL_EVALUATION, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, START);
        }
        clock.set(DEADLINE.plus(Duration.ofDays(2)));

        assertThat(deadlines.isDue(interview)).isFalse();
    }
}
