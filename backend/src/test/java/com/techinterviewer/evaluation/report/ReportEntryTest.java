package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportEntryTest {

    private static final UUID Q = new UUID(0, 1);

    private static ReportEntry entry(boolean skipped, UUID evaluationId, Double score) {
        return new ReportEntry(Q, Q, 1, QuestionKind.TECHNICAL, Section.QUESTIONING, null, 3, skipped, evaluationId, score, "", Set.of());
    }

    @Test
    void anAnsweredQuestionWithoutEvaluationAwaitsItAndASkippedOneDoesNot() {
        assertThat(entry(false, null, null).awaitsEvaluation()).isTrue();
        assertThat(entry(false, new UUID(1, 1), 50.0).awaitsEvaluation()).isFalse();
        assertThat(entry(true, null, null).awaitsEvaluation()).isFalse();
    }

    @Test
    void anEvaluationIdAndAScoreComeTogether() {
        assertThatIllegalArgumentException().isThrownBy(() -> entry(false, new UUID(1, 1), null));
        assertThatIllegalArgumentException().isThrownBy(() -> entry(false, null, 50.0));
    }

    @Test
    void aSkippedAnswerHasNoEvaluation() {
        assertThatIllegalArgumentException().isThrownBy(() -> entry(true, new UUID(1, 1), 50.0));
    }

    @Test
    void theFlagsAreCopied() {
        Set<EvaluationFlag> flags = new HashSet<>(Set.of(EvaluationFlag.OFF_TOPIC));
        ReportEntry entry = new ReportEntry(Q, Q, 1, QuestionKind.TECHNICAL, Section.QUESTIONING, null, 3, false, new UUID(1, 1), 50.0, "s", flags);

        flags.add(EvaluationFlag.INJECTION_SUSPECTED);

        assertThat(entry.flags()).containsExactly(EvaluationFlag.OFF_TOPIC);
    }

    @Test
    void anUnassessableAnswerIsNeitherAwaitingNorEvaluatedNorSkipped() {
        ReportEntry entry = new ReportEntry(new java.util.UUID(0, 1), new java.util.UUID(0, 1), 1, com.techinterviewer.interview.domain.QuestionKind.TECHNICAL,
                com.techinterviewer.interview.domain.Section.QUESTIONING, null, 3, false, null, null, "", Set.of(),
                com.techinterviewer.interview.domain.EvaluationFailure.SAFETY_BLOCK);

        assertThat(entry.awaitsEvaluation()).as("it was given up, the report does not wait for it").isFalse();
        assertThat(entry.unassessable()).isEqualTo(com.techinterviewer.interview.domain.EvaluationFailure.SAFETY_BLOCK);
    }

    @Test
    void anUnassessableAnswerCannotCarryAnEvaluationOrBeSkipped() {
        java.util.UUID id = new java.util.UUID(0, 1);
        var kind = com.techinterviewer.interview.domain.QuestionKind.TECHNICAL;
        var section = com.techinterviewer.interview.domain.Section.QUESTIONING;
        var reason = com.techinterviewer.interview.domain.EvaluationFailure.PROVIDER_FAILURE;

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReportEntry(id, id, 1, kind, section, null, 3, false, new java.util.UUID(1, 1), 50.0, "s", Set.of(), reason));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReportEntry(id, id, 1, kind, section, null, 3, true, null, null, "", Set.of(), reason));
    }
}
