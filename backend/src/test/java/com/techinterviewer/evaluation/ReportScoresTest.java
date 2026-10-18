package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.ReportScores.Coverage;
import com.techinterviewer.evaluation.ReportScores.IntegrityNote;
import com.techinterviewer.evaluation.ReportScores.SectionScore;
import com.techinterviewer.evaluation.ReportScores.SkillScore;
import com.techinterviewer.evaluation.ReportScores.TopicScore;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportScoresTest {

    private static final Coverage COVERAGE = new Coverage(1, 0, 0, 1, 1);

    @Test
    void overallScoreMustBeAPercentageWhenPresent() {
        assertThatThrownBy(() -> new ReportScores(100.01, Confidence.HIGH, List.of(), List.of(), List.of(), COVERAGE, List.of()))
                .hasMessageContaining("overallScore");
        assertThatThrownBy(() -> new ReportScores(-0.01, Confidence.HIGH, List.of(), List.of(), List.of(), COVERAGE, List.of()))
                .hasMessageContaining("overallScore");
        assertThat(new ReportScores(100.0, Confidence.HIGH, List.of(), List.of(), List.of(), COVERAGE, List.of()).insufficientEvidence()).isFalse();
        assertThat(new ReportScores(0.0, Confidence.HIGH, List.of(), List.of(), List.of(), COVERAGE, List.of()).insufficientEvidence()).isFalse();
        assertThat(new ReportScores(null, Confidence.LOW, List.of(), List.of(), List.of(), COVERAGE, List.of()).insufficientEvidence()).isTrue();
    }

    @Test
    void requiredPartsAreChecked() {
        assertThatThrownBy(() -> new ReportScores(50.0, null, List.of(), List.of(), List.of(), COVERAGE, List.of()))
                .hasMessageContaining("overallConfidence");
        assertThatThrownBy(() -> new ReportScores(50.0, Confidence.LOW, List.of(), List.of(), List.of(), null, List.of()))
                .hasMessageContaining("coverage");
    }

    @Test
    void listsAreDefensivelyCopied() {
        List<SectionScore> sections = new ArrayList<>(List.of(new SectionScore(Section.CODING, 50, 1)));
        ReportScores scores = new ReportScores(50.0, Confidence.LOW, sections, List.of(), List.of(), COVERAGE, List.of());

        sections.clear();

        assertThat(scores.sections()).hasSize(1);
        assertThatThrownBy(() -> scores.sections().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sectionSkillAndTopicScoresAreRangeChecked() {
        assertThatThrownBy(() -> new SectionScore(Section.CODING, 100.01, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionScore(Section.CODING, 50, 0)).hasMessageContaining("evidenceCount");
        assertThatThrownBy(() -> new SectionScore(null, 50, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillScore("java", -1, Confidence.LOW, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillScore("java", 50, Confidence.LOW, 0, 3)).hasMessageContaining("evidenceCount");
        assertThatThrownBy(() -> new SkillScore("java", 50, Confidence.LOW, 1, 0.99)).hasMessageContaining("averageDifficulty");
        assertThatThrownBy(() -> new SkillScore("java", 50, Confidence.LOW, 1, 5.01)).hasMessageContaining("averageDifficulty");
        assertThatThrownBy(() -> new SkillScore("java", 50, null, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillScore(" ", 50, Confidence.LOW, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SkillScore("java", 100, Confidence.HIGH, 1, 5).averageDifficulty()).isEqualTo(5.0);
        assertThat(new SkillScore("java", 0, Confidence.HIGH, 1, 1).score()).isEqualTo(0.0);
        assertThatThrownBy(() -> new TopicScore("java/x", 101, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopicScore("java/x", 50, 0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopicScore("java/x", 50, 1, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopicScore(" ", 50, 1, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coverageCannotBeNegative() {
        assertThatThrownBy(() -> new Coverage(-1, 0, 0, 0, 0)).hasMessageContaining("questionsAnswered");
        assertThatThrownBy(() -> new Coverage(0, -1, 0, 0, 0)).hasMessageContaining("skipped");
        assertThatThrownBy(() -> new Coverage(0, 0, -1, 0, 0)).hasMessageContaining("followUps");
        assertThatThrownBy(() -> new Coverage(0, 0, 0, -1, 0)).hasMessageContaining("topicsCovered");
        assertThatThrownBy(() -> new Coverage(0, 0, 0, 0, -1)).hasMessageContaining("skillsCovered");
        assertThat(new Coverage(0, 0, 0, 0, 0).skipped()).isZero();
    }

    @Test
    void integrityNoteNeedsItsCountToMatchItsQuestions() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new IntegrityNote(EvaluationFlag.OFF_TOPIC, 2, List.of(id), "m")).hasMessageContaining("answerCount");
        assertThatThrownBy(() -> new IntegrityNote(EvaluationFlag.OFF_TOPIC, 0, List.of(), "m")).hasMessageContaining("answerCount");
        assertThatThrownBy(() -> new IntegrityNote(EvaluationFlag.OFF_TOPIC, 1, List.of(id), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityNote(null, 1, List.of(id), "m")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IntegrityNote(EvaluationFlag.OFF_TOPIC, 1, List.of(id), "m").questionIds()).containsExactly(id);
    }
}
