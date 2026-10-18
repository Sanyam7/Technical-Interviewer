package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.evaluation.domain.Confidence;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Score of one skill, weighted by the difficulty of the questions that backed it.")
public record SkillScoreResponse(
        @Schema(description = "Display name of the skill", example = "Spring Boot") String skill,
        @Schema(description = "Catalog slug of the skill", example = "spring-boot") String slug,
        @Schema(description = "0 to 100, two decimals", example = "81.0") double score,
        @Schema(description = "How much evidence stands behind the score") Confidence confidence,
        @Schema(description = "Number of scored answers behind the score", example = "4") int evidenceCount,
        @Schema(description = "Mean difficulty level of those answers, 1 to 5", example = "3.25") double avgDifficulty) {
}
