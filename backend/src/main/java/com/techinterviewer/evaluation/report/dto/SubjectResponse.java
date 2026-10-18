package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A technology or topic of the catalog.")
public record SubjectResponse(
        @Schema(description = "A skill slug, or a skill/topic key", example = "spring-boot/transactions") String key,
        @Schema(description = "Display name", example = "Spring Boot / Transactions") String name) {
}
