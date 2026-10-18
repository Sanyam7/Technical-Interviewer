package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportValueTypesTest {

    private static final UUID EVALUATION = UUID.randomUUID();

    // ---- SectionScore --------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 76.0, 100.0})
    void aSectionScoreBetweenZeroAndOneHundredIsAccepted(double score) {
        assertThat(new SectionScore(Section.CODING, score).score()).isEqualTo(score);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY})
    void aSectionScoreOutsideTheRangeIsRefused(double score) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SectionScore(Section.CODING, score)).withMessageContaining("score");
    }

    @Test
    void aSectionScoreNeedsASection() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SectionScore(null, 50)).withMessageContaining("section");
    }

    // ---- Coverage ------------------------------------------------------------------------------------------------

    @Test
    void coverageHoldsNonNegativeCounts() {
        Coverage coverage = new Coverage(9, 1, 3, 43);

        assertThat(coverage.questionsAnswered()).isEqualTo(9);
        assertThat(coverage.skipped()).isEqualTo(1);
        assertThat(coverage.followUps()).isEqualTo(3);
        assertThat(coverage.minutesUsed()).isEqualTo(43);
        assertThat(new Coverage(0, 0, 0, 0).minutesUsed()).isZero();
    }

    @Test
    void coverageRefusesNegativeCounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Coverage(-1, 0, 0, 0)).withMessageContaining("questionsAnswered");
        assertThatIllegalArgumentException().isThrownBy(() -> new Coverage(0, -1, 0, 0)).withMessageContaining("skipped");
        assertThatIllegalArgumentException().isThrownBy(() -> new Coverage(0, 0, -1, 0)).withMessageContaining("followUps");
        assertThatIllegalArgumentException().isThrownBy(() -> new Coverage(0, 0, 0, -1)).withMessageContaining("minutesUsed");
    }

    // ---- IntegrityNote -------------------------------------------------------------------------------------------

    @Test
    void anIntegrityNoteNamesTheFlagTheEvaluationAndTheRemark() {
        IntegrityNote note = new IntegrityNote(EvaluationFlag.OFF_TOPIC, EVALUATION, "Did not address the question.");

        assertThat(note.flag()).isEqualTo(EvaluationFlag.OFF_TOPIC);
        assertThat(note.evaluationId()).isEqualTo(EVALUATION);
        assertThat(note.note()).isEqualTo("Did not address the question.");
    }

    @Test
    void anIntegrityNoteIsCompleteAndBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IntegrityNote(null, EVALUATION, "n")).withMessageContaining("flag");
        assertThatIllegalArgumentException().isThrownBy(() -> new IntegrityNote(EvaluationFlag.OFF_TOPIC, null, "n")).withMessageContaining("evaluationId");
        assertThatIllegalArgumentException().isThrownBy(() -> new IntegrityNote(EvaluationFlag.OFF_TOPIC, EVALUATION, " ")).withMessageContaining("note");
        assertThat(new IntegrityNote(EvaluationFlag.OFF_TOPIC, EVALUATION, "n".repeat(500)).note()).hasSize(500);
        assertThatIllegalArgumentException().isThrownBy(() -> new IntegrityNote(EvaluationFlag.OFF_TOPIC, EVALUATION, "n".repeat(501)));
    }

    // ---- NarrativeEntry ------------------------------------------------------------------------------------------

    @Test
    void aNarrativeEntryCitesAtLeastOneEvaluation() {
        assertThat(new NarrativeEntry("Strong on SQL", List.of(EVALUATION)).evaluationIds()).containsExactly(EVALUATION);
        assertThatIllegalArgumentException().isThrownBy(() -> new NarrativeEntry("text", List.of())).withMessageContaining("at least one evaluation");
        assertThatIllegalArgumentException().isThrownBy(() -> new NarrativeEntry("text", null)).withMessageContaining("at least one evaluation");
    }

    @Test
    void aNarrativeEntryTextIsPresentAndBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NarrativeEntry(" ", List.of(EVALUATION))).withMessageContaining("text");
        assertThat(new NarrativeEntry("t".repeat(1000), List.of(EVALUATION)).text()).hasSize(1000);
        assertThatIllegalArgumentException().isThrownBy(() -> new NarrativeEntry("t".repeat(1001), List.of(EVALUATION)));
    }

    @Test
    void aNarrativeEntryCannotBeChangedThroughTheCallersList() {
        List<UUID> ids = new ArrayList<>(List.of(EVALUATION));
        NarrativeEntry entry = new NarrativeEntry("t", ids);

        ids.add(UUID.randomUUID());

        assertThat(entry.evaluationIds()).containsExactly(EVALUATION);
    }

    // ---- ReportNarrativeDocument ---------------------------------------------------------------------------------

    private static NarrativeEntry entry(int n) {
        return new NarrativeEntry("entry " + n, List.of(EVALUATION));
    }

    @Test
    void aNarrativeHasASummaryEntriesAndAStudyPlan() {
        ReportNarrativeDocument document = new ReportNarrativeDocument("Summary", List.of(entry(1)), List.of(entry(2)), List.of("Read X"));

        assertThat(document.summary()).isEqualTo("Summary");
        assertThat(document.strengths()).containsExactly(entry(1));
        assertThat(document.improvementAreas()).containsExactly(entry(2));
        assertThat(document.studyPlan()).containsExactly("Read X");
    }

    @Test
    void aNarrativeMayHaveNoEntriesAtAll() {
        ReportNarrativeDocument document = new ReportNarrativeDocument("Summary", null, null, null);

        assertThat(document.strengths()).isEmpty();
        assertThat(document.improvementAreas()).isEmpty();
        assertThat(document.studyPlan()).isEmpty();
    }

    @Test
    void theSummaryIsPresentAndBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument(" ", List.of(), List.of(), List.of())).withMessageContaining("summary");
        assertThat(new ReportNarrativeDocument("s".repeat(2000), List.of(), List.of(), List.of()).summary()).hasSize(2000);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument("s".repeat(2001), List.of(), List.of(), List.of()));
    }

    @Test
    void eachListHoldsAtMostTenEntries() {
        List<NarrativeEntry> ten = new ArrayList<>();
        List<String> tenSteps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(entry(i));
            tenSteps.add("step " + i);
        }
        assertThat(new ReportNarrativeDocument("s", ten, ten, tenSteps).studyPlan()).hasSize(10);

        List<NarrativeEntry> eleven = new ArrayList<>(ten);
        eleven.add(entry(10));
        List<String> elevenSteps = new ArrayList<>(tenSteps);
        elevenSteps.add("step 10");
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument("s", eleven, List.of(), List.of())).withMessageContaining("strengths");
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument("s", List.of(), eleven, List.of())).withMessageContaining("improvementAreas");
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument("s", List.of(), List.of(), elevenSteps)).withMessageContaining("studyPlan");
    }

    @Test
    void studyPlanStepsAreNotBlankAndBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument("s", List.of(), List.of(), List.of(" "))).withMessageContaining("studyPlan");
        assertThat(new ReportNarrativeDocument("s", List.of(), List.of(), List.of("x".repeat(500))).studyPlan()).hasSize(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportNarrativeDocument("s", List.of(), List.of(), List.of("x".repeat(501))));
    }

    @Test
    void theNarrativeListsAreImmutable() {
        ReportNarrativeDocument document = new ReportNarrativeDocument("s", List.of(entry(1)), List.of(), List.of("a"));

        assertThatThrownBy(() -> document.strengths().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> document.studyPlan().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
