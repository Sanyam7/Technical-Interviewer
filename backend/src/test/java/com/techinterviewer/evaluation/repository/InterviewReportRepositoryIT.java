package com.techinterviewer.evaluation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.evaluation.report.InterviewReport;
import com.techinterviewer.evaluation.report.ReportSkillScore;
import com.techinterviewer.evaluation.report.ReportSkillScoreId;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class InterviewReportRepositoryIT extends PersistenceTestBase {

    private static final Instant GENERATED = Fixtures.NOW.plusSeconds(120);
    private static final List<SectionScore> SECTIONS =
            List.of(new SectionScore(Section.INTRODUCTION, 80.0), new SectionScore(Section.QUESTIONING, 64.25));

    private InterviewReport load(InterviewReport report) {
        return inTransaction(() -> reports.findById(report.getId()).orElseThrow());
    }

    private void readyWithEverything(InterviewReport report, UUID evaluationId) {
        report.markGenerating();
        report.markReady(
                new BigDecimal("71.456"), Recommendation.PROFICIENT, SECTIONS, Fixtures.narrative(evaluationId),
                Fixtures.integrityNotes(evaluationId), Fixtures.coverage(), "report-v1", Fixtures.REPORT_LLM_CALL, GENERATED);
    }

    @Test
    void aPendingReportStoresOnlyItsStatusAndCounters() {
        Scenario scenario = newScenario();
        InterviewReport original = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        inTransaction(() -> reports.saveAndFlush(original));

        InterviewReport loaded = load(original);

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getInterviewId()).isEqualTo(scenario.interviewId());
        assertThat(loaded.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(loaded.getAttempts()).isZero();
        assertThat(loaded.getOverallScore()).isNull();
        assertThat(loaded.getRecommendation()).isNull();
        assertThat(loaded.getSectionScores()).isNull();
        assertThat(loaded.getNarrative()).isNull();
        assertThat(loaded.getIntegrityNotes()).isNull();
        assertThat(loaded.getCoverage()).isNull();
        assertThat(loaded.getErrorMessage()).isNull();
        assertThat(loaded.getPromptVersion()).isNull();
        assertThat(loaded.getLlmCallId()).isNull();
        assertThat(loaded.getGeneratedAt()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(jdbc.queryForObject("select narrative is null and coverage is null and section_scores is null from reports where id = ?",
                Boolean.class, original.getId())).isTrue();
    }

    @Test
    void aReadyReportKeepsEveryFieldAndEveryJsonDocument() {
        Scenario scenario = newScenario();
        UUID evaluationId = UuidV7.generate();
        InterviewReport original = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        readyWithEverything(original, evaluationId);
        inTransaction(() -> reports.saveAndFlush(original));

        InterviewReport loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(loaded.getOverallScore()).isEqualTo(new BigDecimal("71.46"));
        assertThat(loaded.getRecommendation()).isEqualTo(Recommendation.PROFICIENT);
        assertThat(loaded.getSectionScores()).isEqualTo(SECTIONS);
        assertThat(loaded.getNarrative()).isEqualTo(Fixtures.narrative(evaluationId));
        assertThat(loaded.getIntegrityNotes()).isEqualTo(Fixtures.integrityNotes(evaluationId));
        assertThat(loaded.getCoverage()).isEqualTo(Fixtures.coverage());
        assertThat(loaded.getAttempts()).isEqualTo(1);
        assertThat(loaded.getErrorMessage()).isNull();
        assertThat(loaded.getPromptVersion()).isEqualTo("report-v1");
        assertThat(loaded.getLlmCallId()).isEqualTo(Fixtures.REPORT_LLM_CALL);
        assertThat(loaded.getGeneratedAt()).isEqualTo(GENERATED);
        assertThat(jdbc.queryForObject("select status from reports where id = ?", String.class, original.getId())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select recommendation from reports where id = ?", String.class, original.getId())).isEqualTo("PROFICIENT");
        assertThat(jdbc.queryForObject("select section_scores -> 1 ->> 'section' from reports where id = ?", String.class, original.getId()))
                .isEqualTo("QUESTIONING");
        assertThat(jdbc.queryForObject("select narrative -> 'strengths' -> 0 -> 'evaluationIds' ->> 0 from reports where id = ?", String.class, original.getId()))
                .isEqualTo(evaluationId.toString());
        assertThat(jdbc.queryForObject("select coverage ->> 'questionsAnswered' from reports where id = ?", String.class, original.getId())).isEqualTo("9");
        assertThat(jdbc.queryForObject("select narrative -> 'furtherReading' -> 0 ->> 'source' from reports where id = ?", String.class, original.getId()))
                .isEqualTo("java/concurrency.md");
        // A narrative stored before further reading existed still reads, with none.
        jdbc.update("update reports set narrative = narrative - 'furtherReading' where id = ?", original.getId());
        assertThat(inTransaction(() -> reports.findById(original.getId()).orElseThrow().getNarrative().furtherReading())).isEmpty();
        assertThat(jdbc.queryForObject("select integrity_notes -> 0 ->> 'flag' from reports where id = ?", String.class, original.getId()))
                .isEqualTo("INJECTION_SUSPECTED");
    }

    @Test
    void aReportWithoutEvidenceIsReadyWithoutScoreRecommendationOrNarrative() {
        Scenario scenario = newScenario();
        InterviewReport original = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        original.markGenerating();
        original.markReady(null, null, List.of(), null, List.of(), new Coverage(0, 0, 0, 3), null, null, GENERATED);
        inTransaction(() -> reports.saveAndFlush(original));

        InterviewReport loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(loaded.getOverallScore()).isNull();
        assertThat(loaded.getRecommendation()).isNull();
        assertThat(loaded.getNarrative()).isNull();
        assertThat(loaded.getSectionScores()).isEmpty();
        assertThat(loaded.getIntegrityNotes()).isEmpty();
    }

    @Test
    void aFailedReportKeepsItsMessageAndAttemptsAndIsRetriedInPlace() {
        Scenario scenario = newScenario();
        InterviewReport original = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        original.markGenerating();
        original.markFailed("2 answers await evaluation");
        inTransaction(() -> reports.saveAndFlush(original));

        InterviewReport failed = load(original);
        assertThat(failed).usingRecursiveComparison().isEqualTo(original);
        assertThat(failed.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("2 answers await evaluation");
        assertThat(failed.getAttempts()).isEqualTo(1);

        UUID evaluationId = UuidV7.generate();
        inTransaction(() -> {
            InterviewReport managed = reports.findById(original.getId()).orElseThrow();
            readyWithEverything(managed, evaluationId);
        });

        InterviewReport retried = load(original);
        assertThat(retried.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getErrorMessage()).as("the old error is cleared").isNull();
        assertThat(retried.getNarrative()).isEqualTo(Fixtures.narrative(evaluationId));
    }

    @Test
    void anInterviewHasAtMostOneReport() {
        Scenario scenario = newScenario();
        inTransaction(() -> reports.saveAndFlush(InterviewReport.pending(scenario.interviewId(), Fixtures.NOW)));

        assertThatThrownBy(() -> inTransaction(() -> reports.saveAndFlush(InterviewReport.pending(scenario.interviewId(), Fixtures.NOW))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("reports", "interview_id", scenario.interviewId())).isOne();
    }

    @Test
    void aReportNeedsARealInterviewAndARecordedModelCall() {
        Scenario scenario = newScenario();
        assertThatThrownBy(() -> inTransaction(() -> reports.saveAndFlush(InterviewReport.pending(UuidV7.generate(), Fixtures.NOW))))
                .isInstanceOf(DataIntegrityViolationException.class);
        InterviewReport unknownCall = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        unknownCall.markGenerating();
        unknownCall.markReady(null, null, List.of(), null, List.of(), Fixtures.coverage(), null, UuidV7.generate(), GENERATED);

        assertThatThrownBy(() -> inTransaction(() -> reports.saveAndFlush(unknownCall))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsAScoreOutsideZeroToHundredAnUnknownRecommendationAndNegativeAttempts() {
        Scenario scenario = newScenario();
        String insert = "insert into reports (id, interview_id, status, overall_score, recommendation, attempts) values (?, ?, ?, ?, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), scenario.interviewId(), "READY", new BigDecimal("100.01"), "STRONG", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), scenario.interviewId(), "READY", new BigDecimal("50"), "AMAZING", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), scenario.interviewId(), "DONE", new BigDecimal("50"), "STRONG", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), scenario.interviewId(), "READY", new BigDecimal("50"), "STRONG", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aReportIsFoundByInterviewAndOnlyForTheCandidateWhoOwnsTheInterview() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        inTransaction(() -> reports.saveAndFlush(report));

        assertThat(reports.findByInterviewId(scenario.interviewId())).get().extracting(InterviewReport::getId).isEqualTo(report.getId());
        assertThat(reports.findByInterviewId(other.interviewId())).isEmpty();
        assertThat(reports.findByInterviewIdAndCandidateId(scenario.interviewId(), scenario.userId()))
                .get().extracting(InterviewReport::getId).isEqualTo(report.getId());
        assertThat(reports.findByInterviewIdAndCandidateId(scenario.interviewId(), other.userId())).isEmpty();
        assertThat(reports.findByInterviewIdAndCandidateId(scenario.interviewId(), UuidV7.generate())).isEmpty();
        assertThat(reports.findByInterviewIdAndCandidateId(UuidV7.generate(), scenario.userId())).isEmpty();
    }

    // ---- skill scores ---------------------------------------------------------------------------------------------

    @Test
    void aSkillScoreKeepsItsCompositeKeyAndEveryField() {
        Scenario scenario = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        inTransaction(() -> reports.saveAndFlush(report));
        long skill = skillIds(1).getFirst();
        ReportSkillScore original = new ReportSkillScore(report.getId(), skill, new BigDecimal("82.5"), Confidence.MEDIUM, 4, new BigDecimal("3.25"));
        inTransaction(() -> skillScores.saveAndFlush(original));

        ReportSkillScore loaded = inTransaction(() -> skillScores.findById(new ReportSkillScoreId(report.getId(), skill)).orElseThrow());

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getReportId()).isEqualTo(report.getId());
        assertThat(loaded.getSkillId()).isEqualTo(skill);
        assertThat(loaded.getScore()).isEqualTo(new BigDecimal("82.50"));
        assertThat(loaded.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(loaded.getEvidenceCount()).isEqualTo(4);
        assertThat(loaded.getAvgDifficulty()).isEqualTo(new BigDecimal("3.25"));
        assertThat(jdbc.queryForObject("select confidence from report_skill_scores where report_id = ?", String.class, report.getId())).isEqualTo("MEDIUM");
    }

    @Test
    void theSkillScoresOfAReportComeBestFirstAndNeverIncludeAnotherReport() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        InterviewReport otherReport = InterviewReport.pending(other.interviewId(), Fixtures.NOW);
        List<Long> skills = skillIds(4);
        inTransaction(() -> {
            reports.saveAndFlush(report);
            reports.saveAndFlush(otherReport);
            skillScores.saveAndFlush(new ReportSkillScore(report.getId(), skills.get(0), new BigDecimal("50"), Confidence.LOW, 1, BigDecimal.ONE));
            skillScores.saveAndFlush(new ReportSkillScore(report.getId(), skills.get(1), new BigDecimal("90"), Confidence.HIGH, 5, new BigDecimal("5")));
            skillScores.saveAndFlush(new ReportSkillScore(report.getId(), skills.get(3), new BigDecimal("50"), Confidence.LOW, 1, BigDecimal.ONE));
            skillScores.saveAndFlush(new ReportSkillScore(otherReport.getId(), skills.get(2), new BigDecimal("99"), Confidence.HIGH, 5, BigDecimal.ONE));
        });

        assertThat(skillScores.findByIdReportIdOrderByScoreDescIdSkillIdAsc(report.getId()))
                .extracting(ReportSkillScore::getSkillId).containsExactly(skills.get(1), skills.get(0), skills.get(3));
        assertThat(skillScores.findByIdReportIdOrderByScoreDescIdSkillIdAsc(UuidV7.generate())).isEmpty();
    }

    @Test
    void aSkillCanBeScoredOnlyOncePerReportAndMustExist() {
        Scenario scenario = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        inTransaction(() -> reports.saveAndFlush(report));
        long skill = skillIds(1).getFirst();
        inTransaction(() -> skillScores.saveAndFlush(new ReportSkillScore(report.getId(), skill, new BigDecimal("50"), Confidence.LOW, 1, BigDecimal.ONE)));

        assertThatThrownBy(() -> inTransaction(() -> skillScores.saveAndFlush(
                new ReportSkillScore(report.getId(), skill, new BigDecimal("60"), Confidence.LOW, 1, BigDecimal.ONE))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> skillScores.saveAndFlush(
                new ReportSkillScore(report.getId(), Long.MAX_VALUE, new BigDecimal("60"), Confidence.LOW, 1, BigDecimal.ONE))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> skillScores.saveAndFlush(
                new ReportSkillScore(UuidV7.generate(), skill, new BigDecimal("60"), Confidence.LOW, 1, BigDecimal.ONE))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("report_skill_scores", "report_id", report.getId())).isOne();
    }

    @Test
    void theDatabaseRejectsASkillScoreOutOfRange() {
        Scenario scenario = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        inTransaction(() -> reports.saveAndFlush(report));
        long skill = skillIds(1).getFirst();
        String insert = "insert into report_skill_scores (report_id, skill_id, score, confidence, evidence_count, avg_difficulty) values (?, ?, ?, ?, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, report.getId(), skill, new BigDecimal("100.5"), "LOW", 1, new BigDecimal("2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, report.getId(), skill, new BigDecimal("50"), "CERTAIN", 1, new BigDecimal("2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, report.getId(), skill, new BigDecimal("50"), "LOW", -1, new BigDecimal("2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, report.getId(), skill, new BigDecimal("50"), "LOW", 1, new BigDecimal("0.99")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, report.getId(), skill, new BigDecimal("50"), "LOW", 1, new BigDecimal("5.01")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void skillScoresDisappearWithTheirReport() {
        Scenario scenario = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        inTransaction(() -> {
            reports.saveAndFlush(report);
            skillScores.saveAndFlush(new ReportSkillScore(report.getId(), skillIds(1).getFirst(), new BigDecimal("50"), Confidence.LOW, 1, BigDecimal.ONE));
        });

        jdbc.update("delete from reports where id = ?", report.getId());

        assertThat(count("report_skill_scores", "report_id", report.getId())).isZero();
    }

    @Test
    void theCollectionsOfAReportReadFromTheDatabaseCannotBeModifiedThroughTheGetters() {
        Scenario scenario = newScenario();
        InterviewReport original = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        readyWithEverything(original, UuidV7.generate());
        inTransaction(() -> reports.saveAndFlush(original));

        InterviewReport loaded = load(original);

        assertThatThrownBy(() -> loaded.getSectionScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.getIntegrityNotes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
