package com.techinterviewer.evaluation;

import static com.techinterviewer.evaluation.ReportAnswers.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportAnswerTest {

    private static ReportAnswer technical(UUID thread, String topic, int difficulty, boolean skipped, Double score, Set<EvaluationFlag> flags) {
        return new ReportAnswer(id(1), thread, 1, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, skipped, score, flags);
    }

    @Test
    void answeredQuestionNeedsAScoreInRange() {
        assertThatThrownBy(() -> technical(id(1), "java/x", 3, false, null, Set.of())).hasMessageContaining("score");
        assertThatThrownBy(() -> technical(id(1), "java/x", 3, false, 100.01, Set.of())).hasMessageContaining("score");
        assertThatThrownBy(() -> technical(id(1), "java/x", 3, false, -0.01, Set.of())).hasMessageContaining("score");
        assertThat(technical(id(1), "java/x", 3, false, 100.0, Set.of()).score()).isEqualTo(100.0);
        assertThat(technical(id(1), "java/x", 3, false, 0.0, Set.of()).score()).isEqualTo(0.0);
    }

    @Test
    void skippedQuestionHasNeitherScoreNorFlags() {
        assertThatThrownBy(() -> technical(id(1), "java/x", 3, true, 10.0, Set.of())).hasMessageContaining("skipped");
        assertThatThrownBy(() -> technical(id(1), "java/x", 3, true, null, Set.of(EvaluationFlag.OFF_TOPIC)))
                .hasMessageContaining("skipped");
        assertThat(technical(id(1), "java/x", 3, true, null, Set.of()).skipped()).isTrue();
    }

    @Test
    void topicMustBeASkillSlashTopicKey() {
        assertThatThrownBy(() -> technical(id(1), "java", 3, false, 50.0, Set.of())).hasMessageContaining("skill/topic");
        assertThatThrownBy(() -> technical(id(1), "/x", 3, false, 50.0, Set.of())).hasMessageContaining("skill/topic");
        assertThatThrownBy(() -> technical(id(1), " ", 3, false, 50.0, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(technical(id(1), "java/x", 3, false, 50.0, Set.of()).skill()).isEqualTo("java");
        assertThat(technical(id(1), null, 3, false, 50.0, Set.of()).skill()).isNull();
    }

    @Test
    void difficultyIsOneToFive() {
        assertThatThrownBy(() -> technical(id(1), "java/x", 0, false, 50.0, Set.of())).hasMessageContaining("difficulty");
        assertThatThrownBy(() -> technical(id(1), "java/x", 6, false, 50.0, Set.of())).hasMessageContaining("difficulty");
        assertThat(technical(id(1), "java/x", 1, false, 50.0, Set.of()).difficulty()).isEqualTo(1);
        assertThat(technical(id(1), "java/x", 5, false, 50.0, Set.of()).difficulty()).isEqualTo(5);
    }

    @Test
    void sequenceNumberStartsAtOne() {
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 0, QuestionKind.TECHNICAL, Section.QUESTIONING, "java/x", 3, false, 1.0, Set.of()))
                .hasMessageContaining("sequenceNo");
    }

    @Test
    void aRootIsItsOwnThreadAndAFollowUpIsNot() {
        assertThatThrownBy(() -> technical(id(2), "java/x", 3, false, 50.0, Set.of())).hasMessageContaining("thread");
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 1, QuestionKind.FOLLOW_UP, Section.QUESTIONING, "java/x", 3, false, 1.0, Set.of()))
                .hasMessageContaining("thread");
        assertThat(new ReportAnswer(id(2), id(1), 2, QuestionKind.FOLLOW_UP, Section.QUESTIONING, "java/x", 3, false, 1.0, Set.of()).isFollowUp())
                .isTrue();
    }

    @Test
    void kindAndSectionMustAgree() {
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 1, QuestionKind.TECHNICAL, Section.CODING, "java/x", 3, false, 1.0, Set.of()))
                .hasMessageContaining("cannot belong");
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 1, QuestionKind.CODING, Section.QUESTIONING, null, 3, false, 1.0, Set.of()))
                .hasMessageContaining("cannot belong");
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 1, QuestionKind.SYSTEM_DESIGN, Section.CODING, null, 3, false, 1.0, Set.of()))
                .hasMessageContaining("cannot belong");
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 1, QuestionKind.INTRO, Section.QUESTIONING, null, 3, false, 1.0, Set.of()))
                .hasMessageContaining("cannot belong");
        assertThatThrownBy(() -> new ReportAnswer(id(1), id(1), 1, QuestionKind.INTRO, Section.INTRODUCTION, "java/x", 3, false, 1.0, Set.of()))
                .hasMessageContaining("cannot belong");
        assertThatThrownBy(() -> new ReportAnswer(id(2), id(1), 2, QuestionKind.FOLLOW_UP, Section.INTRODUCTION, null, 3, false, 1.0, Set.of()))
                .hasMessageContaining("cannot belong");
    }
}
