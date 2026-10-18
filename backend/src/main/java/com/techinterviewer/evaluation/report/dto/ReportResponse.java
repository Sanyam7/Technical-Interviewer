package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The report of an interview. Only a READY report carries scores; a PENDING or GENERATING one is being made and a
 * FAILED one says why it is not available and that asking again can help. Every number is computed by the server from
 * validated ratings; nothing here exposes the questions' expected key points or the rubrics.
 */
@Schema(description = "The report of an interview. READY carries the scores and, when the narrator was available, the written part; "
        + "PENDING and GENERATING are still being made (HTTP 202); FAILED carries errorMessage and retryable.")
public record ReportResponse(
        UUID interviewId,
        ReportStatus status,
        @Schema(description = "READY reports only. COMPLETE: every answer was assessed. PARTIAL: some answers could not be assessed (see "
                + "assessment); the scores cover the rest. INCONCLUSIVE: too little could be assessed, so there is no overall score and "
                + "no recommendation.", nullable = true) @Nullable ReportOutcome outcome,
        @Schema(description = "0 to 100, computed from the assessable answers only; null when the interview has too little evidence "
                + "(nothing but the introduction was answered) and when the outcome is INCONCLUSIVE",
                nullable = true, example = "72.5") @Nullable Double overallScore,
        @Schema(description = "STRONG, PROFICIENT, DEVELOPING or NOT_YET; null when there is no overall score or too little evidence for a band",
                nullable = true) @Nullable Recommendation recommendation,
        List<SectionScoreResponse> sections,
        List<SkillScoreResponse> skills,
        @Schema(description = "Written summary; null when the narrator was unavailable", nullable = true) @Nullable String summary,
        List<NarrativeItemResponse> strengths,
        List<NarrativeItemResponse> improvementAreas,
        List<String> studyPlan,
        @Schema(description = "Knowledge-base sections the study plan points to; empty when the narrative was written without reference material")
        List<ReadingResponse> furtherReading,
        List<IntegrityNoteResponse> integrityNotes,
        @Schema(nullable = true) @Nullable CoverageResponse coverage,
        @Schema(description = "READY reports only", nullable = true) @Nullable AssessmentResponse assessment,
        @Schema(description = "READY reports only: missed concepts, misconceptions, weak topics and repeated mistakes; null for reports "
                + "made before knowledge gaps were analysed", nullable = true) @Nullable KnowledgeGapsResponse knowledgeGaps,
        @Schema(description = "READY reports only: scores by assessment area, what was not assessed and the recommendation cap; null for "
                + "reports made before assessment areas existed", nullable = true) @Nullable AssessmentAreasResponse assessmentAreas,
        @Schema(nullable = true) @Nullable Instant generatedAt,
        @Schema(description = "How many times generating the report was started", example = "1") int attempts,
        @Schema(description = "True when POST /report/retry can make progress (PENDING and FAILED)") boolean retryable,
        @Schema(description = "Why the report is not available (FAILED only)", nullable = true) @Nullable String errorMessage) {

    public ReportResponse {
        sections = List.copyOf(sections);
        skills = List.copyOf(skills);
        strengths = List.copyOf(strengths);
        improvementAreas = List.copyOf(improvementAreas);
        studyPlan = List.copyOf(studyPlan);
        furtherReading = List.copyOf(furtherReading);
        integrityNotes = List.copyOf(integrityNotes);
    }
}
