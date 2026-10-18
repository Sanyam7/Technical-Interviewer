package com.techinterviewer.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The candidate's own profile. A profile that was never edited has null fields and no skills.")
public record ProfileResponse(
        @Nullable String headline,
        @Nullable Integer yearsExperience,
        @Nullable String jobTitle,
        List<ProfileSkillResponse> skills,
        Instant updatedAt) {

    public ProfileResponse {
        skills = List.copyOf(skills);
    }
}
