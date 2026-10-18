package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The answer that backs a statement of the report.")
public record EvidenceResponse(
        @Schema(description = "The question that was answered") UUID questionId,
        @Schema(description = "The beginning of the candidate's own answer") String excerpt) {
}
