package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.Confidence;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "The result of one assessment area. FUNDAMENTALS: concept and internals questions; PRACTICAL: scenario questions; "
        + "PROBLEM_SOLVING: debugging questions, coding and system design; PROJECT: questions about the candidate's own experience; "
        + "COMMUNICATION: the communication rating of every answer that has one.")
public record AreaScoreResponse(
        AssessmentArea area,
        @Schema(description = "The interview's plan meant to assess this area") boolean planned,
        @Schema(description = "0 to 100; null when nothing in the area could be assessed", nullable = true, example = "68.5") @Nullable Double score,
        @Schema(description = "Scored answers behind the score (a design discussion counts once)", example = "4") int evidenceCount,
        @Schema(description = "How much evidence stands behind the score; null without a score", nullable = true) @Nullable Confidence confidence,
        @Schema(description = "The part of the overall score's weight that comes from this area, 0 to 1; null for COMMUNICATION, which is not "
                + "a part of the overall score", nullable = true, example = "0.55") @Nullable Double shareOfOverall,
        @Schema(description = "Answers in this area whose automated evaluation was unsure of itself; a human review is recommended", example = "0")
        int lowConfidenceAnswers) {
}
