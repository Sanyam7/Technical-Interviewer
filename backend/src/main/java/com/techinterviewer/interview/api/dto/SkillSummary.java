package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A catalog skill an interview covers.")
public record SkillSummary(
        @Schema(description = "Skill slug.", example = "spring-boot") String slug,
        @Schema(description = "Display name.", example = "Spring Boot") String name) {
}
