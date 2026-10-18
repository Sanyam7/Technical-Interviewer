package com.techinterviewer.profile.dto;

import com.techinterviewer.profile.ProfileLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A catalog skill and how the candidate rates themselves in it.")
public record ProfileSkillRequest(
        @Schema(description = "Slug of an active catalog skill; each skill may appear once.", example = "java")
        @NotNull @Size(max = 60) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "must be a skill slug such as spring-boot")
        String skill,
        @Schema(description = "1 (beginner) to 5 (expert).", minimum = "1", maximum = "5", example = "4")
        @NotNull @Min(ProfileLimits.RATING_MIN) @Max(ProfileLimits.RATING_MAX)
        Integer selfRating) {
}
