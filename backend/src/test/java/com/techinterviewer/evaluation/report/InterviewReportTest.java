package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.persistence.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The status flow of a report, without a database. */
class InterviewReportTest {

    private static final UUID INTERVIEW = UuidV7.generate();
    private static final UUID EVALUATION = UuidV7.generate();
    private static final Instant T1 = Fixtures.NOW.plusSeconds(30);
    private static final List<SectionScore> SECTIONS = List.of(new SectionScore(Section.QUESTIONING, 76.0));

    private static InterviewReport pending() {
        return InterviewReport.pending(INTERVIEW, Fixtures.NOW);
    }

    private static InterviewReport generating() {
        InterviewReport report = pending();
        report.markGenerating();
        return report;
    }

    private static void ready(InterviewReport report) {
        report.markReady(new BigDecimal("72.5"), Recommendation.PROFICIENT, SECTIONS, Fixtures.narrative(EVALUATION),
                Fixtures.integrityNotes(EVALUATION), Fixtures.coverage(), "report-1.0", UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d04"), T1);
    }

    // ---- creation -------------------------------------------------------------------------------------------------

    @Test
    void aNewReportIsPendingWithoutAttemptsOrContent() {
        InterviewReport report = pending();

        assertThat(report.getInterviewId()).isEqualTo(INTERVIEW);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(report.getAttempts()).isZero();
        assertThat(report.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(report.getOverallScore()).isNull();
        assertThat(report.getRecommendation()).isNull();
        assertThat(report.getSectionScores()).isNull();
        assertThat(report.getNarrative()).isNull();
        assertThat(report.getIntegrityNotes()).isNull();
        assertThat(report.getCoverage()).isNull();
        assertThat(report.getErrorMessage()).isNull();
        assertThat(report.getPromptVersion()).isNull();
        assertThat(report.getLlmCallId()).isNull();
        assertThat(report.getGeneratedAt()).isNull();
        assertThat(UuidV7.isV7(report.getId())).isTrue();
    }

    @Test
    void creationRequiresTheInterviewAndATime() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewReport.pending(null, Fixtures.NOW)).withMessageContaining("interviewId");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewReport.pending(INTERVIEW, null)).withMessageContaining("now");
    }

    // ---- the flow -------------------------------------------------------------------------------------------------

    @Test
    void generatingCountsTheAttempt() {
        InterviewReport report = pending();

        report.markGenerating();

        assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);
        assertThat(report.getAttempts()).isEqualTo(1);
    }

    @Test
    void aReadyReportHoldsTheScoresTheNarrativeAndTheGenerationTime() {
        InterviewReport report = generating();

        ready(report);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getOverallScore()).isEqualTo(new BigDecimal("72.50"));
        assertThat(report.getRecommendation()).isEqualTo(Recommendation.PROFICIENT);
        assertThat(report.getSectionScores()).isEqualTo(SECTIONS);
        assertThat(report.getNarrative()).isEqualTo(Fixtures.narrative(EVALUATION));
        assertThat(report.getIntegrityNotes()).isEqualTo(Fixtures.integrityNotes(EVALUATION));
        assertThat(report.getCoverage()).isEqualTo(Fixtures.coverage());
        assertThat(report.getPromptVersion()).isEqualTo("report-1.0");
        assertThat(report.getLlmCallId()).isEqualTo(UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d04"));
        assertThat(report.getGeneratedAt()).isEqualTo(T1);
        assertThat(report.getErrorMessage()).isNull();
        assertThat(report.getAttempts()).isEqualTo(1);
    }

    @Test
    void aReportWithoutANarrativeCanStillBeReady() {
        InterviewReport report = generating();

        report.markReady(new BigDecimal("40"), Recommendation.DEVELOPING, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getNarrative()).isNull();
        assertThat(report.getPromptVersion()).isNull();
        assertThat(report.getLlmCallId()).isNull();
        assertThat(report.getIntegrityNotes()).isEmpty();
    }

    @Test
    void aReportWithoutEvidenceIsReadyWithNeitherScoreNorRecommendation() {
        InterviewReport report = generating();

        report.markReady(null, null, List.of(), null, List.of(), new Coverage(0, 0, 0, 3), null, null, T1);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getOverallScore()).isNull();
        assertThat(report.getRecommendation()).isNull();
        assertThat(report.getSectionScores()).isEmpty();
    }

    @Test
    void aRecommendationNeedsAnOverallScore() {
        InterviewReport onlyRecommendation = generating();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> onlyRecommendation.markReady(null, Recommendation.STRONG, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1))
                .withMessageContaining("needs an overallScore");
        assertThat(onlyRecommendation.getStatus()).as("a refused call changes nothing").isEqualTo(ReportStatus.GENERATING);
        assertThat(onlyRecommendation.getGeneratedAt()).isNull();
    }

    @Test
    void aScoreCanStandWithoutARecommendationWhenTheEvidenceIsTooThin() {
        InterviewReport report = generating();

        report.markReady(new BigDecimal("55.5"), null, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getOverallScore()).isEqualByComparingTo("55.50");
        assertThat(report.getRecommendation()).isNull();
    }

    @Test
    void theOverallScoreIsRoundedAndRangeChecked() {
        InterviewReport report = generating();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> report.markReady(new BigDecimal("100.01"), Recommendation.STRONG, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1))
                .withMessageContaining("overallScore");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);

        report.markReady(new BigDecimal("99.999"), Recommendation.STRONG, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1);

        assertThat(report.getOverallScore()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void readyRequiresTheMandatoryParts() {
        InterviewReport report = generating();
        Coverage coverage = Fixtures.coverage();

        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(null, null, null, null, List.of(), coverage, null, null, T1)).withMessageContaining("sectionScores");
        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(null, null, List.of(), null, null, coverage, null, null, T1)).withMessageContaining("integrityNotes");
        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(null, null, List.of(), null, List.of(), null, null, null, T1)).withMessageContaining("coverage");
        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(null, null, List.of(), null, List.of(), coverage, null, null, null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(null, null, List.of(), null, List.of(), coverage, " ", null, T1)).withMessageContaining("promptVersion");
        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(null, null, List.of(), null, List.of(), coverage, "v".repeat(21), null, T1));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);
    }

    @Test
    void aFailedAttemptRecordsTheMessageAndCanBeRetried() {
        InterviewReport report = generating();

        report.markFailed("2 answers await evaluation");

        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(report.getErrorMessage()).isEqualTo("2 answers await evaluation");
        assertThat(report.getOverallScore()).isNull();

        report.markGenerating();

        assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);
        assertThat(report.getAttempts()).isEqualTo(2);
        assertThat(report.getErrorMessage()).as("a new attempt clears the old error").isNull();

        ready(report);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getAttempts()).isEqualTo(2);
        assertThat(report.getErrorMessage()).isNull();
    }

    @Test
    void theErrorMessageIsPresentAndBoundedByTheColumn() {
        InterviewReport report = generating();

        assertThatIllegalArgumentException().isThrownBy(() -> report.markFailed(" ")).withMessageContaining("errorMessage");
        assertThatIllegalArgumentException().isThrownBy(() -> report.markFailed("e".repeat(501)));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);

        report.markFailed("e".repeat(500));

        assertThat(report.getErrorMessage()).hasSize(500);
    }

    // ---- illegal moves --------------------------------------------------------------------------------------------

    @Test
    void aPendingReportCannotBeReadyOrFailedWithoutGenerating() {
        InterviewReport report = pending();

        assertThatIllegalStateException().isThrownBy(() -> ready(report)).withMessageContaining("PENDING");
        assertThatIllegalStateException().isThrownBy(() -> report.markFailed("x")).withMessageContaining("PENDING");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void aGeneratingReportCannotStartAgain() {
        InterviewReport report = generating();

        assertThatIllegalStateException().isThrownBy(report::markGenerating).withMessageContaining("GENERATING");
        assertThat(report.getAttempts()).isEqualTo(1);
    }

    @Test
    void aReadyReportIsFinal() {
        InterviewReport report = generating();
        ready(report);

        assertThatIllegalStateException().isThrownBy(report::markGenerating).withMessageContaining("READY");
        assertThatIllegalStateException().isThrownBy(() -> report.markFailed("x"));
        assertThatIllegalStateException().isThrownBy(() -> ready(report));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getAttempts()).isEqualTo(1);
    }

    @Test
    void aFailedReportCannotBecomeReadyOrFailWithoutANewAttempt() {
        InterviewReport report = generating();
        report.markFailed("boom");

        assertThatIllegalStateException().isThrownBy(() -> ready(report));
        assertThatIllegalStateException().isThrownBy(() -> report.markFailed("again"));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(report.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void theStoredListsCannotBeModifiedThroughTheGetters() {
        InterviewReport report = generating();
        ready(report);
        List<IntegrityNote> notes = report.getIntegrityNotes();
        ReportNarrativeDocument narrative = report.getNarrative();

        assertThatThrownBy(() -> report.getSectionScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(notes::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(narrative).isNotNull();
    }

    // ---- the outcome --------------------------------------------------------------------------------------------

    @Test
    void aReportMarkedReadyWithoutAnOutcomeIsComplete() {
        InterviewReport report = generating();

        ready(report);

        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.COMPLETE);
    }

    @Test
    void aNewReportIsCompleteUntilItIsReady() {
        assertThat(pending().getOutcome()).isEqualTo(ReportOutcome.COMPLETE);
    }

    @Test
    void aPartialReportKeepsItsScoreAndRecommendation() {
        InterviewReport report = generating();

        report.markReady(ReportOutcome.PARTIAL, new BigDecimal("72.5"), Recommendation.PROFICIENT, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1);

        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(report.getOverallScore()).isEqualByComparingTo("72.5");
        assertThat(report.getRecommendation()).isEqualTo(Recommendation.PROFICIENT);
    }

    @Test
    void anInconclusiveReportHasNoScoreAndNoRecommendationAndRefusesBoth() {
        assertThatIllegalArgumentException().isThrownBy(() -> generating().markReady(
                ReportOutcome.INCONCLUSIVE, new BigDecimal("72.5"), null, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1));
        assertThatIllegalArgumentException().isThrownBy(() -> generating().markReady(
                ReportOutcome.INCONCLUSIVE, new BigDecimal("72.5"), Recommendation.PROFICIENT, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1));
        InterviewReport report = generating();

        report.markReady(ReportOutcome.INCONCLUSIVE, null, null, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.getOverallScore()).isNull();
        assertThat(report.getRecommendation()).isNull();
    }

    @Test
    void aRefusedMarkReadyLeavesTheReportGeneratingSoTheAttemptCanFail() {
        InterviewReport report = generating();

        assertThatIllegalArgumentException().isThrownBy(() -> report.markReady(
                ReportOutcome.INCONCLUSIVE, new BigDecimal("1"), null, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);
        report.markFailed("failed");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
    }

    @Test
    void theOutcomeIsRequired() {
        assertThatThrownBy(() -> generating().markReady(null, null, null, SECTIONS, null, List.of(), Fixtures.coverage(), null, null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
