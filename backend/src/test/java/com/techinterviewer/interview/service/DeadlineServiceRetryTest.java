package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestAppProperties;
import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.engine.StateTransition;
import com.techinterviewer.persistence.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** What the lazy deadline does when somebody else changes the interview while it is applying TIME_UP. */
class DeadlineServiceRetryTest {

    private static final Instant START = Fixtures.NOW;
    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d31");

    private final TestClock clock = new TestClock(START);
    private final InterviewLoader loader = mock(InterviewLoader.class);
    private final InterviewWriter writer = mock(InterviewWriter.class);
    private final TransitionApplier transitions = mock(TransitionApplier.class);
    private final List<InterviewEvent> applied = new ArrayList<>();
    private DeadlineService deadlines;
    private Interview interview;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        deadlines = new DeadlineService(
                clock,
                TestAppProperties.withJwt("x", false, "tech-interviewer", Duration.ofMinutes(15)),
                new InterviewTransactions(manager),
                loader,
                transitions,
                writer);
        interview = Interview.create(USER, UUID.randomUUID(), Fixtures.plan(), START);
        interview.start(START, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, START);
        clock.set(START.plus(Duration.ofMinutes(61)));
        when(transitions.edge(any(), any(), any())).thenAnswer(call -> {
            applied.add(call.getArgument(1));
            return new StateTransition(InterviewState.INTRODUCTION, InterviewEvent.TIME_UP, InterviewState.FINAL_EVALUATION, null, null);
        });
    }

    private ObjectOptimisticLockingFailureException lost() {
        return new ObjectOptimisticLockingFailureException(Interview.class, interview.getId());
    }

    @Test
    void aLostRaceIsRetriedAndTheInterviewJudgedAgainOnItsNewestState() {
        Interview alreadyEnded = Interview.create(USER, UUID.randomUUID(), Fixtures.plan(), START);
        alreadyEnded.start(START, 60);
        alreadyEnded.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, START);
        alreadyEnded.applyTransition(InterviewState.INTRODUCTION, InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP, START);
        when(loader.load(interview.getId(), USER)).thenThrow(lost()).thenThrow(lost()).thenReturn(alreadyEnded);

        boolean ended = deadlines.expireIfDue(interview.getId(), USER);

        assertThat(ended).isFalse();
        assertThat(applied).isEmpty();
    }

    @Test
    void theRetryEndsTheInterviewWhenNobodyElseDidAndReportsIt() {
        when(loader.load(interview.getId(), USER)).thenThrow(lost()).thenReturn(interview);

        boolean ended = deadlines.expireIfDue(interview.getId(), USER);

        assertThat(ended).isTrue();
        assertThat(applied).containsExactly(InterviewEvent.TIME_UP);
    }

    @Test
    void afterThreeLostRacesTheCallerIsToldToReloadWithTheCurrentState() {
        ApiException conflict = new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "changed").withProperty("currentState", "QUESTIONING");
        when(loader.load(interview.getId(), USER)).thenThrow(lost());
        when(writer.conflict(any(), any(), any())).thenReturn(conflict);

        assertThatThrownBy(() -> deadlines.expireIfDue(interview.getId(), USER)).isSameAs(conflict);
        assertThat(applied).isEmpty();
    }

    @Test
    void exactlyThreeAttemptsAreMade() {
        List<Integer> loads = new ArrayList<>();
        when(loader.load(interview.getId(), USER)).thenAnswer(call -> {
            loads.add(loads.size() + 1);
            throw lost();
        });
        when(writer.conflict(any(), any(), any())).thenReturn(new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "changed"));

        assertThatThrownBy(() -> deadlines.expireIfDue(interview.getId(), USER)).isInstanceOf(ApiException.class);

        assertThat(loads).hasSize(3);
    }
}
