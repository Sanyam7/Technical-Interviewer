package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.EngineFixtures.CONCURRENCY;
import static com.techinterviewer.interview.engine.EngineFixtures.T0;
import static com.techinterviewer.interview.engine.EngineFixtures.answer;
import static com.techinterviewer.interview.engine.EngineFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnsweredQuestionTest {

    private static AnsweredQuestion of(QuestionKind kind, Section section, String topic, int difficulty, int depth) {
        return new AnsweredQuestion(answer(1), question(1), kind, section, topic, difficulty, depth, T0, List.of("kp1"));
    }

    @Test
    void validCombinationsAreAccepted() {
        assertThat(of(QuestionKind.INTRO, Section.INTRODUCTION, null, 3, 0).isRoot()).isTrue();
        assertThat(of(QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, 0).isRoot()).isTrue();
        assertThat(of(QuestionKind.CODING, Section.CODING, null, 3, 0).isRoot()).isTrue();
        assertThat(of(QuestionKind.CODING, Section.CODING, "java/streams", 3, 0).topic()).isEqualTo("java/streams");
        assertThat(of(QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, 3, 0).isRoot()).isTrue();
        assertThat(of(QuestionKind.FOLLOW_UP, Section.QUESTIONING, CONCURRENCY, 3, 1).isRoot()).isFalse();
        assertThat(of(QuestionKind.FOLLOW_UP, Section.CODING, null, 3, 2).isRoot()).isFalse();
        assertThat(of(QuestionKind.FOLLOW_UP, Section.SYSTEM_DESIGN, null, 3, 2).followUpDepth()).isEqualTo(2);
    }

    @Test
    void kindSectionAndDepthMustAgree() {
        assertThatThrownBy(() -> of(QuestionKind.INTRO, Section.QUESTIONING, null, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.INTRO, Section.INTRODUCTION, CONCURRENCY, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.INTRO, Section.INTRODUCTION, null, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.TECHNICAL, Section.CODING, CONCURRENCY, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.CODING, Section.QUESTIONING, null, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.CODING, Section.CODING, null, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.SYSTEM_DESIGN, Section.CODING, null, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, 3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.FOLLOW_UP, Section.INTRODUCTION, null, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(QuestionKind.FOLLOW_UP, Section.QUESTIONING, CONCURRENCY, 3, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesAreRangeChecked() {
        assertThatThrownBy(() -> of(QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 0, 0)).hasMessageContaining("difficulty");
        assertThatThrownBy(() -> of(QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 6, 0)).hasMessageContaining("difficulty");
        assertThatThrownBy(() -> of(QuestionKind.FOLLOW_UP, Section.QUESTIONING, CONCURRENCY, 3, 3)).hasMessageContaining("followUpDepth");
        assertThat(of(QuestionKind.FOLLOW_UP, Section.SYSTEM_DESIGN, null, 3, 6).followUpDepth()).as("a design thread goes deeper").isEqualTo(6);
        assertThatThrownBy(() -> of(QuestionKind.FOLLOW_UP, Section.SYSTEM_DESIGN, null, 3, 7)).hasMessageContaining("followUpDepth");
        assertThatThrownBy(() -> of(QuestionKind.TECHNICAL, Section.QUESTIONING, " ", 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnsweredQuestion(null, question(1), QuestionKind.INTRO, Section.INTRODUCTION, null, 3, 0, T0, List.of()))
                .hasMessageContaining("answerId");
        assertThatThrownBy(() -> new AnsweredQuestion(answer(1), null, QuestionKind.INTRO, Section.INTRODUCTION, null, 3, 0, T0, List.of()))
                .hasMessageContaining("questionId");
        assertThatThrownBy(() -> new AnsweredQuestion(answer(1), question(1), QuestionKind.INTRO, Section.INTRODUCTION, null, 3, 0, null, List.of()))
                .hasMessageContaining("askedAt");
        assertThatThrownBy(() -> new AnsweredQuestion(answer(1), question(1), QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, 0, T0,
                List.of(" "))).isInstanceOf(IllegalArgumentException.class);
    }
}
