package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.NarrativeEntry;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.evaluation.report.dto.CoverageResponse;
import com.techinterviewer.evaluation.report.dto.EvidenceResponse;
import com.techinterviewer.evaluation.report.dto.IntegrityNoteResponse;
import com.techinterviewer.evaluation.report.dto.NarrativeItemResponse;
import com.techinterviewer.evaluation.report.dto.ReportResponse;
import com.techinterviewer.evaluation.report.dto.SectionScoreResponse;
import com.techinterviewer.evaluation.report.dto.SkillScoreResponse;
import com.techinterviewer.interview.domain.Section;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mapping of stored reports to responses, on plain objects. */
class ReportResponseMapperTest {

    private static final UUID INTERVIEW = new UUID(5, 5);
    private static final UUID EVALUATION_A = new UUID(6, 1);
    private static final UUID EVALUATION_B = new UUID(6, 2);
    private static final UUID QUESTION_A = new UUID(7, 1);
    private static final UUID QUESTION_B = new UUID(7, 2);
    private static final Instant GENERATED = Instant.parse("2026-10-05T13:00:00Z");

    private static final Map<UUID, EvidenceResponse> EVIDENCE = Map.of(
            EVALUATION_A, new EvidenceResponse(QUESTION_A, "answer A"),
            EVALUATION_B, new EvidenceResponse(QUESTION_B, "answer B"));

    private static InterviewReport generating() {
        InterviewReport report = InterviewReport.pending(INTERVIEW, GENERATED.minusSeconds(60));
        report.markGenerating();
        return report;
    }

    private static InterviewReport ready() {
        InterviewReport report = generating();
        report.markReady(
                new BigDecimal("72.5"), Recommendation.PROFICIENT,
                List.of(new SectionScore(Section.QUESTIONING, 76.0), new SectionScore(Section.CODING, 68.5)),
                new ReportNarrativeDocument("The summary.",
                        List.of(new NarrativeEntry("Strong on proxies", List.of(EVALUATION_A, EVALUATION_B))),
                        List.of(new NarrativeEntry("Indexes", List.of(EVALUATION_B))),
                        List.of("Read the docs", "Practice")),
                List.of(new IntegrityNote(EvaluationFlag.INJECTION_SUSPECTED, EVALUATION_A, "A note.")),
                new Coverage(9, 1, 3, 43), "report-1.0", null, GENERATED);
        return report;
    }

    private static ReportSkillScore skill(InterviewReport report, long skillId, String score, Confidence confidence, int evidence, String difficulty) {
        return new ReportSkillScore(report.getId(), skillId, new BigDecimal(score), confidence, evidence, new BigDecimal(difficulty));
    }

    @Test
    void aReadyReportIsMappedFieldByField() {
        InterviewReport report = ready();
        List<ReportSkillScore> skills = List.of(skill(report, 2, "81.25", Confidence.HIGH, 4, "3.25"), skill(report, 1, "58", Confidence.MEDIUM, 2, "2.5"));

        ReportResponse response = ReportResponseMapper.of(report, skills,
                Map.of(1L, new SkillRef(1, "postgresql", "PostgreSQL"), 2L, new SkillRef(2, "spring-boot", "Spring Boot")), EVIDENCE);

        assertThat(response).isEqualTo(new ReportResponse(
                INTERVIEW, ReportStatus.READY, com.techinterviewer.evaluation.domain.ReportOutcome.COMPLETE, 72.5, Recommendation.PROFICIENT,
                List.of(new SectionScoreResponse(Section.QUESTIONING, 76.0), new SectionScoreResponse(Section.CODING, 68.5)),
                List.of(new SkillScoreResponse("Spring Boot", "spring-boot", 81.25, Confidence.HIGH, 4, 3.25),
                        new SkillScoreResponse("PostgreSQL", "postgresql", 58.0, Confidence.MEDIUM, 2, 2.5)),
                "The summary.",
                List.of(new NarrativeItemResponse("Strong on proxies", List.of(EVIDENCE.get(EVALUATION_A), EVIDENCE.get(EVALUATION_B)))),
                List.of(new NarrativeItemResponse("Indexes", List.of(EVIDENCE.get(EVALUATION_B)))),
                List.of("Read the docs", "Practice"),
                List.of(),
                List.of(new IntegrityNoteResponse(EvaluationFlag.INJECTION_SUSPECTED, QUESTION_A, "A note.")),
                new CoverageResponse(9, 1, 3, 43),
                new com.techinterviewer.evaluation.report.dto.AssessmentResponse(1.0, 0, 0, List.of()), null, null, GENERATED, 1, false, null));
    }

    @Test
    void anEvaluationCitedTwiceByOneItemShowsItsEvidenceOnce() {
        InterviewReport report = generating();
        report.markReady(new BigDecimal("60"), Recommendation.DEVELOPING, List.of(),
                new ReportNarrativeDocument("S.", List.of(new NarrativeEntry("Twice", List.of(EVALUATION_A, EVALUATION_A))), List.of(), List.of()),
                List.of(), new Coverage(1, 0, 0, 1), "report-1.0", null, GENERATED);

        ReportResponse response = ReportResponseMapper.of(report, List.of(), Map.of(), EVIDENCE);

        assertThat(response.strengths()).containsExactly(new NarrativeItemResponse("Twice", List.of(EVIDENCE.get(EVALUATION_A))));
    }

    @Test
    void aReadyReportWithoutNarrativeOrScoreIsMappedWithEmptyListsAndNulls() {
        InterviewReport report = generating();
        report.markReady(null, null, List.of(), null, List.of(), new Coverage(0, 0, 0, 3), null, null, GENERATED);

        ReportResponse response = ReportResponseMapper.of(report, List.of(), Map.of(), Map.of());

        assertThat(response.status()).isEqualTo(ReportStatus.READY);
        assertThat(response.overallScore()).isNull();
        assertThat(response.recommendation()).isNull();
        assertThat(response.summary()).isNull();
        assertThat(response.strengths()).isEmpty();
        assertThat(response.improvementAreas()).isEmpty();
        assertThat(response.studyPlan()).isEmpty();
        assertThat(response.sections()).isEmpty();
        assertThat(response.skills()).isEmpty();
        assertThat(response.integrityNotes()).isEmpty();
        assertThat(response.coverage()).isEqualTo(new CoverageResponse(0, 0, 0, 3));
        assertThat(response.retryable()).isFalse();
    }

    @Test
    void aFailedReportCarriesItsMessageAndIsRetryableButHasNoScores() {
        InterviewReport report = generating();
        report.markFailed("2 answers still await evaluation. Try again shortly.");

        ReportResponse response = ReportResponseMapper.of(report, List.of(), Map.of(), Map.of());

        assertThat(response.status()).isEqualTo(ReportStatus.FAILED);
        assertThat(response.errorMessage()).isEqualTo("2 answers still await evaluation. Try again shortly.");
        assertThat(response.retryable()).isTrue();
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.overallScore()).isNull();
        assertThat(response.coverage()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(response.sections()).isEmpty();
    }

    @Test
    void aGeneratingReportIsNotRetryableAndAPendingOneIs() {
        ReportResponse generating = ReportResponseMapper.of(generating(), List.of(), Map.of(), Map.of());
        ReportResponse pending = ReportResponseMapper.of(InterviewReport.pending(INTERVIEW, GENERATED), List.of(), Map.of(), Map.of());

        assertThat(generating.status()).isEqualTo(ReportStatus.GENERATING);
        assertThat(generating.retryable()).isFalse();
        assertThat(generating.attempts()).isEqualTo(1);
        assertThat(generating.errorMessage()).isNull();
        assertThat(pending.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(pending.retryable()).isTrue();
        assertThat(pending.attempts()).isZero();
    }

    @Test
    void aReportThatWasNeverStartedIsPendingAndRetryable() {
        ReportResponse response = ReportResponseMapper.notStarted(INTERVIEW);

        assertThat(response.interviewId()).isEqualTo(INTERVIEW);
        assertThat(response.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(response.retryable()).isTrue();
        assertThat(response.attempts()).isZero();
        assertThat(response.overallScore()).isNull();
        assertThat(response.sections()).isEmpty();
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void evidenceThatCannotBeResolvedIsLeftOutAndNeverInvented() {
        InterviewReport report = ready();

        ReportResponse response = ReportResponseMapper.of(report, List.of(), Map.of(), Map.of(EVALUATION_B, EVIDENCE.get(EVALUATION_B)));

        assertThat(response.strengths().get(0).evidence()).containsExactly(EVIDENCE.get(EVALUATION_B));
        assertThat(response.integrityNotes()).as("a note about an evaluation that cannot be resolved is not shown").isEmpty();
    }

    @Test
    void aSkillThatIsMissingFromTheCatalogIsAnInconsistency() {
        InterviewReport report = ready();

        assertThatIllegalStateException().isThrownBy(() -> ReportResponseMapper.of(
                report, List.of(skill(report, 9, "50", Confidence.LOW, 1, "2")), Map.of(), EVIDENCE)).withMessageContaining("9");
    }

    @Test
    void citedEvaluationsAreThoseOfTheNarrativeAndTheIntegrityNotes() {
        assertThat(ReportResponseMapper.citedEvaluations(ready())).containsExactlyInAnyOrder(EVALUATION_A, EVALUATION_B, EVALUATION_B, EVALUATION_A);
        assertThat(ReportResponseMapper.citedEvaluations(generating())).isEmpty();
    }

    @Test
    void anExcerptCollapsesWhitespaceAndIsNeverLongerThanTheLimit() {
        assertThat(ReportResponseMapper.excerpt("  line one\n\n   line\ttwo  ")).isEqualTo("line one line two");
        assertThat(ReportResponseMapper.excerpt("a".repeat(ReportResponseMapper.MAX_EXCERPT_LENGTH)))
                .hasSize(ReportResponseMapper.MAX_EXCERPT_LENGTH).doesNotEndWith("...");
        assertThat(ReportResponseMapper.excerpt("a".repeat(ReportResponseMapper.MAX_EXCERPT_LENGTH + 1)))
                .hasSize(ReportResponseMapper.MAX_EXCERPT_LENGTH).endsWith("...");
    }

    @Test
    void furtherReadingShowsTheSectionsButNotTheChunkIds() {
        InterviewReport report = generating();
        UUID chunk = UUID.randomUUID();
        report.markReady(new BigDecimal("60"), Recommendation.DEVELOPING, List.of(),
                new ReportNarrativeDocument("S.", List.of(), List.of(), List.of("Study caching."), List.of(new com.techinterviewer.evaluation.domain.ReadingEntry(
                        chunk, "Caching", "Caching › Cache stampede and hot keys", "system-design/caching.md"))),
                List.of(), new Coverage(1, 0, 0, 1), "report-1.0", null, GENERATED);

        ReportResponse response = ReportResponseMapper.of(report, List.of(), Map.of(), EVIDENCE);

        assertThat(response.furtherReading()).containsExactly(new com.techinterviewer.evaluation.report.dto.ReadingResponse(
                "Caching", "Caching › Cache stampede and hot keys", "system-design/caching.md"));
        assertThat(response.toString()).doesNotContain(chunk.toString());
    }
}
