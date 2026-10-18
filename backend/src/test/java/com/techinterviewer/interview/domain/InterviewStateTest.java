package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterviewStateTest {

    @ParameterizedTest(name = "{0}: terminal={1}, acceptsAnswers={2}")
    @CsvSource({
        "INIT,             false, false",
        "INTRODUCTION,     false, true",
        "QUESTIONING,      false, true",
        "FOLLOW_UP,        false, true",
        "CODING,           false, true",
        "SYSTEM_DESIGN,    false, true",
        "FINAL_EVALUATION, false, false",
        "COMPLETED,        true,  false",
        "ABANDONED,        true,  false"
    })
    void stateProperties(InterviewState state, boolean terminal, boolean acceptsAnswers) {
        assertThat(state.isTerminal()).isEqualTo(terminal);
        assertThat(state.acceptsAnswers()).isEqualTo(acceptsAnswers);
    }

    @Test
    void aTerminalStateNeverAcceptsAnswers() {
        Arrays.stream(InterviewState.values()).filter(InterviewState::isTerminal)
                .forEach(state -> assertThat(state.acceptsAnswers()).isFalse());
    }

    @Test
    void sectionsRunInTheOrderTheyAreDeclaredAndMapToTheirOwnState() {
        assertThat(Section.values()).containsExactly(
                Section.INTRODUCTION, Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);
        for (Section section : Section.values()) {
            assertThat(section.state().name()).isEqualTo(section.name());
        }
    }

    @Test
    void everyStateWhereQuestionsAreAskedIsAStateOfASectionOrFollowUp() {
        Set<InterviewState> asking = EnumSet.noneOf(InterviewState.class);
        Arrays.stream(Section.values()).map(Section::state).forEach(asking::add);
        asking.add(InterviewState.FOLLOW_UP);

        assertThat(Arrays.stream(InterviewState.values()).filter(InterviewState::acceptsAnswers)).containsExactlyInAnyOrderElementsOf(asking);
    }
}
