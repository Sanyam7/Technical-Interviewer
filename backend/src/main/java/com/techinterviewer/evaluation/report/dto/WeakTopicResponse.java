package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A topic answered weakly: its score is below 45, the threshold the adaptive interview also uses.")
public record WeakTopicResponse(
        GapTopicResponse topic,
        @Schema(description = "0 to 100, weighted by difficulty", example = "32.5") double score,
        @Schema(description = "Scored answers behind the score", example = "2") int evidenceCount) {
}
