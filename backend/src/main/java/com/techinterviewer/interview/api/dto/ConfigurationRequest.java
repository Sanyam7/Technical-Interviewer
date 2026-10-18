package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the candidate asks an interview to be. Used on its own to save a configuration and inline in the request that
 * creates an interview. Rules that need the catalog or combine several fields (the duration list, skills that exist and
 * are not repeated, the coding language for the interview type) are checked by the service and reported on the field.
 */
@Schema(description = "An interview configuration. It cannot be changed after it was created.")
public record ConfigurationRequest(
        @Schema(description = "Role interviewed for.", example = "BACKEND")
        @NotNull TargetRole role,
        @Schema(description = "Seniority of the candidate.", example = "MID")
        @NotNull ExperienceLevel experienceLevel,
        @Schema(description = "Difficulty the candidate asks for.", example = "MEDIUM")
        @NotNull InterviewDifficulty difficulty,
        @Schema(description = "Length of the interview in minutes: 15, 30, 45, 60 or 90.", example = "45", allowableValues = {"15", "30", "45", "60", "90"})
        @NotNull Integer durationMinutes,
        @Schema(description = "Shape of the interview.", example = "FULL_LOOP")
        @NotNull InterviewType interviewType,
        @Schema(description = "Slugs of 1 to 6 different active catalog skills.", example = "[\"java\", \"spring-boot\"]")
        @NotNull @Size(min = 1, max = MAX_SKILLS) List<@NotNull @Size(max = 60)
                @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "must be a skill slug such as spring-boot") String> skills,
        @Schema(description = "Language of the coding round: java, javascript, typescript, python or go (GET /catalog/options lists them). Required for CODING and FULL_LOOP "
                + "interviews and not allowed for the other types.", example = "java", nullable = true)
        @Size(max = 20) @Nullable String codingLanguage,
        @Schema(description = "Personalise the interview from the candidate's résumé: its project questions are about experiences the résumé "
                + "states. Creating an interview from such a configuration requires an uploaded résumé.", defaultValue = "false")
        @Nullable Boolean useResume,
        @Schema(description = "How the technical questions are divided between fundamentals, practical scenarios and the candidate's own "
                + "projects. FUNDAMENTALS_FIRST (the default) asks every technology's fundamentals first; BALANCED gives the three similar "
                + "parts; RESUME_FOCUSED is mostly about the candidate's own experience and requires useResume; CUSTOM takes stageMix. Only "
                + "interviews with technical questions (TECHNICAL, FULL_LOOP) use it.", nullable = true, defaultValue = "FUNDAMENTALS_FIRST")
        @Nullable InterviewStrategy strategy,
        @Schema(description = "The stage shares of a CUSTOM strategy (required for CUSTOM, not allowed for the other strategies).", nullable = true)
        @Valid @Nullable StageMixView stageMix) {

    public static final int MAX_SKILLS = 6;

    /** A request with the default strategy. */
    public ConfigurationRequest(TargetRole role, ExperienceLevel experienceLevel, InterviewDifficulty difficulty, Integer durationMinutes,
            InterviewType interviewType, List<String> skills, @Nullable String codingLanguage, @Nullable Boolean useResume) {
        this(role, experienceLevel, difficulty, durationMinutes, interviewType, skills, codingLanguage, useResume, null, null);
    }
}
