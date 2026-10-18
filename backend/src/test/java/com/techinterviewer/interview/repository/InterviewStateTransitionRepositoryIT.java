package com.techinterviewer.interview.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class InterviewStateTransitionRepositoryIT extends PersistenceTestBase {

    private static final Instant T0 = Fixtures.NOW;

    @Test
    void theCreationRowHasNoSourceStateAndKeepsEveryField() {
        Scenario scenario = newScenario();

        InterviewStateTransition loaded = inTransaction(() -> transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(scenario.interviewId()))
                .getFirst();

        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getInterviewId()).isEqualTo(scenario.interviewId());
        assertThat(loaded.getFromState()).isNull();
        assertThat(loaded.getToState()).isEqualTo(InterviewState.INIT);
        assertThat(loaded.getEvent()).isEqualTo(InterviewEvent.CREATE);
        assertThat(loaded.getDetails()).isNull();
        assertThat(loaded.getOccurredAt()).isEqualTo(T0);
        assertThat(jdbc.queryForObject("select from_state is null from interview_state_transitions where id = ?", Boolean.class, loaded.getId()))
                .isTrue();
        assertThat(jdbc.queryForObject("select details is null from interview_state_transitions where id = ?", Boolean.class, loaded.getId()))
                .isTrue();
    }

    @Test
    void aMoveWithDetailsKeepsTheStatesTheEventAndTheJsonDetails() {
        Scenario scenario = newScenario();
        InterviewStateTransition original = InterviewStateTransition.of(
                scenario.interviewId(), InterviewState.QUESTIONING, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED,
                Map.of("reason", "gap in key point kp1", "section", "QUESTIONING"), T0.plusSeconds(30));
        inTransaction(() -> transitions.saveAndFlush(original));

        InterviewStateTransition loaded = inTransaction(() -> transitions.findById(original.getId()).orElseThrow());

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getFromState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(loaded.getToState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(loaded.getEvent()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(loaded.getDetails()).containsExactlyInAnyOrderEntriesOf(Map.of("reason", "gap in key point kp1", "section", "QUESTIONING"));
        assertThat(loaded.getOccurredAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(jdbc.queryForObject("select details ->> 'reason' from interview_state_transitions where id = ?", String.class, original.getId()))
                .isEqualTo("gap in key point kp1");
        assertThat(jdbc.queryForObject("select event from interview_state_transitions where id = ?", String.class, original.getId()))
                .isEqualTo("ANSWER_EVALUATED");
    }

    @Test
    void theRowReturnedByAnEntityTransitionIsTheOneThatIsAudited() {
        Scenario scenario = newScenario();

        inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            interview.start(T0, 30);
            transitions.save(interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0.plusSeconds(1)));
        });

        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(scenario.interviewId()))
                .extracting(InterviewStateTransition::getFromState, InterviewStateTransition::getToState, InterviewStateTransition::getEvent)
                .containsExactly(
                        tuple(null, InterviewState.INIT, InterviewEvent.CREATE),
                        tuple(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START));
    }

    @Test
    void theTrailComesInOrderOfTimeThenIdAndOnlyForItsInterview() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewStateTransition late = InterviewStateTransition.of(
                scenario.interviewId(), InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, null, T0.plusSeconds(20));
        InterviewStateTransition sameInstantFirst = InterviewStateTransition.of(
                scenario.interviewId(), InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, null, T0.plusSeconds(10));
        InterviewStateTransition sameInstantSecond = InterviewStateTransition.of(
                scenario.interviewId(), InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, null, T0.plusSeconds(10));
        inTransaction(() -> {
            transitions.saveAndFlush(late);
            transitions.saveAndFlush(sameInstantFirst);
            transitions.saveAndFlush(sameInstantSecond);
        });

        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(scenario.interviewId()))
                .extracting(InterviewStateTransition::getId)
                .endsWith(sameInstantFirst.getId(), sameInstantSecond.getId(), late.getId())
                .hasSize(4);
        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(other.interviewId())).hasSize(1);
        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(UuidV7.generate())).isEmpty();
    }

    @Test
    void theDatabaseGivesEveryRowAnIncreasingIdentity() {
        Scenario scenario = newScenario();
        InterviewStateTransition first = InterviewStateTransition.of(
                scenario.interviewId(), InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, null, T0);
        InterviewStateTransition second = InterviewStateTransition.of(
                scenario.interviewId(), InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, null, T0);
        assertThat(first.getId()).isNull();

        inTransaction(() -> {
            transitions.saveAndFlush(first);
            transitions.saveAndFlush(second);
        });

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isGreaterThan(first.getId());
    }

    @Test
    void onlyTheCreationRowMayLackASourceState() {
        Scenario scenario = newScenario();

        assertThatThrownBy(() -> jdbc.update(
                "insert into interview_state_transitions (interview_id, from_state, to_state, event) values (?, null, 'QUESTIONING', 'START')",
                scenario.interviewId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into interview_state_transitions (interview_id, from_state, to_state, event) values (?, 'INIT', 'NOT_A_STATE', 'START')",
                scenario.interviewId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTransitionNeedsARealInterview() {
        InterviewStateTransition orphan = InterviewStateTransition.creation(UuidV7.generate(), T0);

        assertThatThrownBy(() -> inTransaction(() -> transitions.saveAndFlush(orphan)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
