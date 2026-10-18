package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How much of the interview the report is based on.")
public record CoverageResponse(
        @Schema(description = "Questions the candidate answered, follow-ups and the introduction included", example = "9") int questionsAnswered,
        @Schema(description = "Questions the candidate skipped", example = "1") int skipped,
        @Schema(description = "Follow-up questions that were asked", example = "3") int followUps,
        @Schema(description = "Minutes between the start and the end of the interview", example = "43") int minutesUsed) {
}
