package com.techinterviewer.evaluation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.evaluation.domain.UnassessableAnswer;
import com.techinterviewer.evaluation.report.InterviewReport;
import com.techinterviewer.evaluation.support.ReportTestBase;
import com.techinterviewer.evaluation.support.ScenarioBuilder;
import com.techinterviewer.evaluation.support.ScenarioBuilder.Asked;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.Section;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** What the database itself enforces about the give-up policy, and how the new columns and documents round-trip. */
class GiveUpPersistenceIT extends ReportTestBase {

    private Asked answered() {
        ScenarioBuilder builder = newInterview();
        Asked asked = builder.technical("java/concurrency", 3, "an answer");
        builder.buildFinished();
        return asked;
    }

    private ScenarioBuilder.Built finished() {
        ScenarioBuilder builder = newInterview();
        builder.technical("java/concurrency", 3, "an answer");
        return builder.buildFinished();
    }

    // ---- answers ---------------------------------------------------------------------------------------------------

    @Test
    void anUnassessableAnswerMustSayWhy() {
        Asked asked = answered();

        assertThatThrownBy(() -> jdbc.update("update answers set evaluation_status = 'UNASSESSABLE' where id = ?", asked.answerId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_answers_unassessable_has_failure");
        jdbc.update("update answers set evaluation_status = 'UNASSESSABLE', evaluation_failure = 'SAFETY_BLOCK' where id = ?", asked.answerId());
        assertThat(jdbc.queryForObject("select evaluation_status from answers where id = ?", String.class, asked.answerId())).isEqualTo("UNASSESSABLE");
    }

    @Test
    void theRetiredFailedStatusAndUnknownReasonsAreRejected() {
        Asked asked = answered();

        assertThatThrownBy(() -> jdbc.update("update answers set evaluation_status = 'FAILED' where id = ?", asked.answerId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_answers_evaluation_status");
        assertThatThrownBy(() -> jdbc.update("update answers set evaluation_failure = 'TIMEOUT' where id = ?", asked.answerId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_answers_evaluation_failure");
        assertThatThrownBy(() -> jdbc.update("update answers set evaluation_attempts = -1 where id = ?", asked.answerId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_answers_evaluation_attempts");
    }

    @Test
    void aNewAnswerHasNoAttemptsNoFailureAndNothingScheduled() {
        Asked asked = answered();

        assertThat(jdbc.queryForObject("select evaluation_attempts from answers where id = ?", Integer.class, asked.answerId())).isZero();
        assertThat(jdbc.queryForObject("select evaluation_failure from answers where id = ?", String.class, asked.answerId())).isNull();
        assertThat(jdbc.queryForObject("select next_evaluation_at from answers where id = ?", java.sql.Timestamp.class, asked.answerId())).isNull();
    }

    @Test
    void thePendingIndexCoversOnlyAnswersThatStillWaitForAnAttempt() {
        String definition = jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'ix_answers_pending'", String.class);

        assertThat(definition).contains("evaluation_status").contains("'PENDING'").doesNotContain("<>");
    }

    // ---- reports ---------------------------------------------------------------------------------------------------

    @Test
    void aReportRowStoredBeforeTheOutcomeExistedIsComplete() {
        ScenarioBuilder.Built built = finished();
        UUID report = UUID.randomUUID();

        jdbc.update("insert into reports (id, interview_id, status) values (?, ?, 'PENDING')", report, built.interviewId());

        assertThat(jdbc.queryForObject("select outcome from reports where id = ?", String.class, report)).isEqualTo("COMPLETE");
    }

    @Test
    void anInconclusiveReportCannotCarryAScoreOrARecommendationInTheDatabaseEither() {
        ScenarioBuilder.Built built = finished();
        UUID report = UUID.randomUUID();
        jdbc.update("insert into reports (id, interview_id, status) values (?, ?, 'READY')", report, built.interviewId());

        assertThatThrownBy(() -> jdbc.update("update reports set outcome = 'INCONCLUSIVE', overall_score = 50 where id = ?", report))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_inconclusive_has_no_verdict");
        assertThatThrownBy(() -> jdbc.update("update reports set outcome = 'INCONCLUSIVE', recommendation = 'STRONG' where id = ?", report))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_inconclusive_has_no_verdict");
        assertThatThrownBy(() -> jdbc.update("update reports set outcome = 'UNKNOWN' where id = ?", report))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_outcome");
        jdbc.update("update reports set outcome = 'INCONCLUSIVE' where id = ?", report);
    }

    @Test
    void theOutcomeAndTheDisclosureOfAPartialReportRoundTrip() {
        ScenarioBuilder.Built built = finished();
        UUID question = UUID.randomUUID();
        Coverage coverage = new Coverage(6, 1, 2, 43, 15, 18, List.of(new UnassessableAnswer(question, 3, Section.QUESTIONING, EvaluationFailure.SAFETY_BLOCK)));
        inTransaction(() -> {
            InterviewReport report = InterviewReport.pending(built.interviewId(), clock.instant());
            report.markGenerating();
            report.markReady(ReportOutcome.PARTIAL, new BigDecimal("72.5"), Recommendation.PROFICIENT, List.of(new SectionScore(Section.QUESTIONING, 76.0)),
                    null, List.of(), coverage, null, null, clock.instant());
            reports.saveAndFlush(report);
        });

        InterviewReport loaded = inTransaction(() -> reports.findByInterviewId(built.interviewId()).orElseThrow());

        assertThat(loaded.getOutcome()).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(loaded.getCoverage()).isEqualTo(coverage);
        assertThat(jdbc.queryForObject("select outcome from reports where interview_id = ?", String.class, built.interviewId())).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("select coverage -> 'unassessable' -> 0 ->> 'reason' from reports where interview_id = ?", String.class,
                built.interviewId())).isEqualTo("SAFETY_BLOCK");
    }

    @Test
    void aCoverageDocumentWrittenBeforeTheAssessmentExistedStillLoadsAsFullyAssessed() {
        ScenarioBuilder.Built built = finished();
        jdbc.update("insert into reports (id, interview_id, status, coverage) values (?, ?, 'READY', "
                + "'{\"questionsAnswered\":8,\"skipped\":1,\"followUps\":2,\"minutesUsed\":43}'::jsonb)", UUID.randomUUID(), built.interviewId());

        InterviewReport loaded = inTransaction(() -> reports.findByInterviewId(built.interviewId()).orElseThrow());

        assertThat(loaded.getCoverage()).isEqualTo(new Coverage(8, 1, 2, 43));
        assertThat(loaded.getCoverage().unassessable()).isEmpty();
        assertThat(loaded.getCoverage().assessmentCoverage()).isEqualByComparingTo("1.00");
    }
}
