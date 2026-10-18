package com.techinterviewer.profile.dto;

import com.techinterviewer.common.validation.SingleLineText;
import com.techinterviewer.profile.ProfileLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The complete profile: whatever is not sent is cleared, and {@code skills} replaces the whole set. */
@Schema(description = "The complete candidate profile. Absent text and number fields are cleared; skills replaces the whole set.")
public record UpdateProfileRequest(
        @Schema(description = "Short self description. Blank is stored as absent.", maxLength = ProfileLimits.HEADLINE_MAX)
        @Size(max = ProfileLimits.HEADLINE_MAX) @SingleLineText @Nullable String headline,
        @Schema(description = "Years of professional experience.", minimum = "0", maximum = "60")
        @Min(0) @Max(ProfileLimits.YEARS_EXPERIENCE_MAX) @Nullable Integer yearsExperience,
        @Schema(description = "Current or target job title. Blank is stored as absent.", maxLength = ProfileLimits.JOB_TITLE_MAX)
        @Size(max = ProfileLimits.JOB_TITLE_MAX) @SingleLineText @Nullable String jobTitle,
        @Schema(description = "Self-rated skills, at most 20, no skill twice.")
        @NotNull @Size(max = ProfileLimits.SKILLS_MAX) List<@NotNull @Valid ProfileSkillRequest> skills) {
}
