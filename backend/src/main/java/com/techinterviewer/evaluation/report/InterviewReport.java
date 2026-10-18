package com.techinterviewer.evaluation.report;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.AssessmentAreas;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.domain.Scores;
import com.techinterviewer.evaluation.domain.SectionScore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The final report of an interview (table {@code reports}); one per interview. The per-skill scores are rows of
 * {@link ReportSkillScore}.
 *
 * <p>The status moves PENDING, GENERATING, then READY (final) or FAILED, and a FAILED report goes back to
 * GENERATING when it is retried ({@link #markGenerating()}, {@link #markReady}, {@link #markFailed}).
 * Every other move throws {@link IllegalStateException}. {@code attempts} counts the times generation started.
 * A READY report has a generation time and no error; a FAILED report has an error message and no scores; an
 * interview without evidence has a READY report with neither an overall score nor a recommendation, and one with
 * too little evidence for a recommendation band has a score only. A READY report states its {@link ReportOutcome}: COMPLETE,
 * PARTIAL (some answers could not be assessed and are disclosed in the coverage) or INCONCLUSIVE (too little could be assessed:
 * neither an overall score nor a recommendation).
 */
@Entity
@Table(name = "reports")
public class InterviewReport extends BaseEntity {

    public static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    public static final int MAX_PROMPT_VERSION_LENGTH = 20;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 14)
    private ReportOutcome outcome = ReportOutcome.COMPLETE;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private @Nullable BigDecimal overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 20)
    private @Nullable Recommendation recommendation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_scores", columnDefinition = "jsonb")
    private @Nullable List<SectionScore> sectionScores;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narrative", columnDefinition = "jsonb")
    private @Nullable ReportNarrativeDocument narrative;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "integrity_notes", columnDefinition = "jsonb")
    private @Nullable List<IntegrityNote> integrityNotes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coverage", columnDefinition = "jsonb")
    private @Nullable Coverage coverage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_gaps", columnDefinition = "jsonb")
    private @Nullable KnowledgeGapReport knowledgeGaps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assessment_areas", columnDefinition = "jsonb")
    private @Nullable AssessmentAreas assessmentAreas;

    @Column(name = "attempts", nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int attempts;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
    private @Nullable String errorMessage;

    @Column(name = "prompt_version", length = MAX_PROMPT_VERSION_LENGTH)
    private @Nullable String promptVersion;

    @Column(name = "llm_call_id")
    private @Nullable UUID llmCallId;

    @Column(name = "generated_at")
    private @Nullable Instant generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InterviewReport() {
    }

    private InterviewReport(UUID interviewId, Instant createdAt) {
        this.interviewId = interviewId;
        this.status = ReportStatus.PENDING;
        this.createdAt = createdAt;
    }

    /** A report that has not been generated yet. */
    public static InterviewReport pending(UUID interviewId, Instant now) {
        return new InterviewReport(Require.nonNull(interviewId, "interviewId"), Require.nonNull(now, "now"));
    }

    /**
     * An attempt to generate the report starts (the first one, or a retry after a failure). Clears the previous error.
     *
     * @throws IllegalStateException when the report is GENERATING or READY already
     */
    public void markGenerating() {
        if (status != ReportStatus.PENDING && status != ReportStatus.FAILED) {
            throw new IllegalStateException("report " + getId() + " is " + status + " and cannot start generating");
        }
        this.status = ReportStatus.GENERATING;
        this.attempts++;
        this.errorMessage = null;
    }

    /**
     * The attempt succeeded. A recommendation needs an overall score; a score can stand without a recommendation
     * (too little evidence for a band: a single scored turn), and an interview without any answered question has
     * neither.
     *
     * @param narrative the written part; absent when the narrator was unavailable
     * @throws IllegalStateException    when the report is not GENERATING
     * @throws IllegalArgumentException when a recommendation is given without a score, or a value is out of range
     */
    public void markReady(
            @Nullable BigDecimal overallScore,
            @Nullable Recommendation recommendation,
            List<SectionScore> sectionScores,
            @Nullable ReportNarrativeDocument narrative,
            List<IntegrityNote> integrityNotes,
            Coverage coverage,
            @Nullable String promptVersion,
            @Nullable UUID llmCallId,
            Instant now) {
        markReady(ReportOutcome.COMPLETE, overallScore, recommendation, sectionScores, narrative, integrityNotes, coverage,
                promptVersion, llmCallId, now);
    }

    /**
     * As {@link #markReady} with the outcome of the assessment.
     *
     * @throws IllegalArgumentException when the outcome is INCONCLUSIVE and a score or a recommendation is given
     */
    public void markReady(
            ReportOutcome outcome,
            @Nullable BigDecimal overallScore,
            @Nullable Recommendation recommendation,
            List<SectionScore> sectionScores,
            @Nullable ReportNarrativeDocument narrative,
            List<IntegrityNote> integrityNotes,
            Coverage coverage,
            @Nullable String promptVersion,
            @Nullable UUID llmCallId,
            Instant now) {
        markReady(outcome, overallScore, recommendation, sectionScores, narrative, integrityNotes, coverage, null,
                promptVersion, llmCallId, now);
    }

    /**
     * As {@link #markReady} with the outcome of the assessment and the knowledge gaps of the interview.
     *
     * @param knowledgeGaps what the evaluations showed the candidate does not know; null when not analysed
     * @throws IllegalArgumentException when the outcome is INCONCLUSIVE and a score or a recommendation is given
     */
    public void markReady(
            ReportOutcome outcome,
            @Nullable BigDecimal overallScore,
            @Nullable Recommendation recommendation,
            List<SectionScore> sectionScores,
            @Nullable ReportNarrativeDocument narrative,
            List<IntegrityNote> integrityNotes,
            Coverage coverage,
            @Nullable KnowledgeGapReport knowledgeGaps,
            @Nullable String promptVersion,
            @Nullable UUID llmCallId,
            Instant now) {
        markReady(outcome, overallScore, recommendation, sectionScores, narrative, integrityNotes, coverage, knowledgeGaps, null, promptVersion,
                llmCallId, now);
    }

    /**
     * As {@link #markReady} with the outcome, the knowledge gaps and the assessment areas of the interview.
     *
     * @param assessmentAreas the breakdown by assessment area (AI_ARCHITECTURE.md section 2.15); null when not computed
     * @throws IllegalArgumentException when the outcome is INCONCLUSIVE and a score or a recommendation is given
     */
    public void markReady(
            ReportOutcome outcome,
            @Nullable BigDecimal overallScore,
            @Nullable Recommendation recommendation,
            List<SectionScore> sectionScores,
            @Nullable ReportNarrativeDocument narrative,
            List<IntegrityNote> integrityNotes,
            Coverage coverage,
            @Nullable KnowledgeGapReport knowledgeGaps,
            @Nullable AssessmentAreas assessmentAreas,
            @Nullable String promptVersion,
            @Nullable UUID llmCallId,
            Instant now) {
        requireGenerating("become READY");
        Require.nonNull(outcome, "outcome");
        if (outcome == ReportOutcome.INCONCLUSIVE && (overallScore != null || recommendation != null)) {
            throw new IllegalArgumentException("an inconclusive report has no overallScore and no recommendation");
        }
        if (overallScore == null && recommendation != null) {
            throw new IllegalArgumentException("a recommendation needs an overallScore");
        }
        BigDecimal score = overallScore == null ? null : Scores.percent(overallScore, "overallScore");
        List<SectionScore> sections = List.copyOf(Require.nonNull(sectionScores, "sectionScores"));
        List<IntegrityNote> notes = List.copyOf(Require.nonNull(integrityNotes, "integrityNotes"));
        Require.nonNull(coverage, "coverage");
        Require.nonNull(now, "now");
        String version = Require.nullOrNotBlank(promptVersion, "promptVersion", MAX_PROMPT_VERSION_LENGTH);
        this.status = ReportStatus.READY;
        this.outcome = outcome;
        this.overallScore = score;
        this.recommendation = recommendation;
        this.sectionScores = sections;
        this.narrative = narrative;
        this.integrityNotes = notes;
        this.coverage = coverage;
        this.knowledgeGaps = knowledgeGaps;
        this.assessmentAreas = assessmentAreas;
        this.promptVersion = version;
        this.llmCallId = llmCallId;
        this.generatedAt = now;
    }

    /**
     * The attempt failed; the report can be retried with {@link #markGenerating()}.
     *
     * @param message what went wrong, not blank, at most {@value #MAX_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException when the report is not GENERATING
     */
    public void markFailed(String message) {
        requireGenerating("fail");
        this.errorMessage = Require.notBlank(message, "errorMessage", MAX_ERROR_MESSAGE_LENGTH);
        this.status = ReportStatus.FAILED;
    }

    private void requireGenerating(String action) {
        if (status != ReportStatus.GENERATING) {
            throw new IllegalStateException("report " + getId() + " is " + status + " and cannot " + action);
        }
    }

    /** The knowledge gaps of the interview; null for a report that is not READY or was made before they were analysed. */
    public @Nullable KnowledgeGapReport getKnowledgeGaps() {
        return knowledgeGaps;
    }

    /** The breakdown by assessment area; null for a report that is not READY or was made before areas existed. */
    public @Nullable AssessmentAreas getAssessmentAreas() {
        return assessmentAreas;
    }

    public UUID getInterviewId() {
        return interviewId;
    }

    public ReportStatus getStatus() {
        return status;
    }

    /** COMPLETE until the report is READY. */
    public ReportOutcome getOutcome() {
        return outcome;
    }

    public @Nullable BigDecimal getOverallScore() {
        return overallScore;
    }

    public @Nullable Recommendation getRecommendation() {
        return recommendation;
    }

    /** The per-section scores; null until the report is READY. */
    public @Nullable List<SectionScore> getSectionScores() {
        return sectionScores == null ? null : Collections.unmodifiableList(sectionScores);
    }

    public @Nullable ReportNarrativeDocument getNarrative() {
        return narrative;
    }

    /** The integrity notes; null until the report is READY. */
    public @Nullable List<IntegrityNote> getIntegrityNotes() {
        return integrityNotes == null ? null : Collections.unmodifiableList(integrityNotes);
    }

    public @Nullable Coverage getCoverage() {
        return coverage;
    }

    public int getAttempts() {
        return attempts;
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    public @Nullable String getPromptVersion() {
        return promptVersion;
    }

    public @Nullable UUID getLlmCallId() {
        return llmCallId;
    }

    public @Nullable Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
