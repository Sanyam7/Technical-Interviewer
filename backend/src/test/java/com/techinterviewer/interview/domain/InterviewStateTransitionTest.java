package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InterviewStateTransitionTest {

    private static final UUID INTERVIEW = UuidV7.generate();

    @Test
    void theCreationRowGoesFromNothingToInitWithTheCreateEvent() {
        InterviewStateTransition row = InterviewStateTransition.creation(INTERVIEW, Fixtures.NOW);

        assertThat(row.getId()).as("assigned by the database on insert").isNull();
        assertThat(row.getInterviewId()).isEqualTo(INTERVIEW);
        assertThat(row.getFromState()).isNull();
        assertThat(row.getToState()).isEqualTo(InterviewState.INIT);
        assertThat(row.getEvent()).isEqualTo(InterviewEvent.CREATE);
        assertThat(row.getDetails()).isNull();
        assertThat(row.getOccurredAt()).isEqualTo(Fixtures.NOW);
    }

    @Test
    void aMoveRecordsBothStatesTheEventAndTheDetails() {
        InterviewStateTransition row = InterviewStateTransition.of(
                INTERVIEW, InterviewState.QUESTIONING, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE,
                Map.of("reason", "slots used"), Fixtures.NOW);

        assertThat(row.getFromState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(row.getToState()).isEqualTo(InterviewState.CODING);
        assertThat(row.getEvent()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(row.getDetails()).containsExactly(Map.entry("reason", "slots used"));
    }

    @Test
    void emptyDetailsAreStoredAsAbsent() {
        InterviewStateTransition row = InterviewStateTransition.of(
                INTERVIEW, InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, Map.of(), Fixtures.NOW);

        assertThat(row.getDetails()).isNull();
    }

    @Test
    void detailsCannotBeChangedAfterwardsNeitherThroughTheArgumentNorTheGetter() {
        Map<String, String> given = new HashMap<>(Map.of("a", "1"));
        InterviewStateTransition row = InterviewStateTransition.of(
                INTERVIEW, InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, given, Fixtures.NOW);

        given.put("b", "2");

        assertThat(row.getDetails()).containsOnlyKeys("a");
        assertThatThrownBy(() -> row.getDetails().put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyCreationMayHaveTheCreateEventOrTargetInit() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                INTERVIEW, InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.CREATE, null, Fixtures.NOW))
                .withMessageContaining("creation()");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                INTERVIEW, InterviewState.QUESTIONING, InterviewState.INIT, InterviewEvent.START, null, Fixtures.NOW))
                .withMessageContaining("INIT");
    }

    @Test
    void everyArgumentIsRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.creation(null, Fixtures.NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.creation(INTERVIEW, null));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                null, InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, null, Fixtures.NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                INTERVIEW, null, InterviewState.INTRODUCTION, InterviewEvent.START, null, Fixtures.NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                INTERVIEW, InterviewState.INIT, null, InterviewEvent.START, null, Fixtures.NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                INTERVIEW, InterviewState.INIT, InterviewState.INTRODUCTION, null, null, Fixtures.NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewStateTransition.of(
                INTERVIEW, InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, null, null));
    }
}
