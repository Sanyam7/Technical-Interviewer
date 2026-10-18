package com.techinterviewer.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A skill the candidate rated.")
public record ProfileSkillResponse(
        @Schema(description = "Slug of the catalog skill.", example = "java") String skill,
        @Schema(description = "Display name of the skill.", example = "Java") String name,
        @Schema(description = "1 (beginner) to 5 (expert).") int selfRating) {
}
