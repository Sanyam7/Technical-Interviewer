package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A saved interview configuration.")
public record ConfigurationResponse(
        @Schema(description = "Configuration id.") UUID id,
        TargetRole role,
        ExperienceLevel experienceLevel,
        @Schema(description = "Difficulty the candidate asked for.") InterviewDifficulty difficulty,
        @Schema(description = "Length of the interview in minutes.", example = "45") int durationMinutes,
        InterviewType interviewType,
        @Schema(description = "The covered skills, by name.") List<SkillSummary> skills,
        @Schema(description = "Language of the coding round; absent unless the interview has one.", nullable = true)
        @Nullable String codingLanguage,
        @Schema(description = "The interview is personalised from the candidate's résumé.") boolean useResume,
        @Schema(description = "How the technical questions are divided between the stages.") InterviewStrategy strategy,
        @Schema(description = "The stage shares the strategy uses: the preset's, or the candidate's for CUSTOM.") StageMixView stageMix,
        Instant createdAt) {

    public ConfigurationResponse {
        skills = List.copyOf(skills);
    }
}
