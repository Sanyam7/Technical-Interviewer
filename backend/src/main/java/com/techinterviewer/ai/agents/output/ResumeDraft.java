package com.techinterviewer.ai.agents.output;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A résumé profile as the model writes it ({@code schemas/resume-extraction.v1.json}). Only the shape is validated here; whether
 * the content is in the résumé is decided by {@code ResumeProfileVerifier}.
 */
public record ResumeDraft(
        @NotNull @Size(max = 10) List<@NotNull @Valid PositionDraft> positions,
        @Nullable Integer statedYears,
        @Nullable String statedYearsEvidence,
        @NotNull @Size(max = 30) List<@NotNull @Valid FactDraft> technologies,
        @NotNull @Size(max = 12) List<@NotNull @Valid FactDraft> skills,
        @NotNull @Size(max = 6) List<@NotNull @Valid FactDraft> domains,
        @NotNull @Size(max = 20) List<@NotNull @Valid ExperienceDraft> experiences) {

    public record PositionDraft(@Nullable String title, @Nullable String organization, @Nullable String start, @Nullable String end,
            @Nullable String evidence) {
    }

    public record FactDraft(@Nullable String name, @Nullable String evidence) {
    }

    public record ExperienceDraft(@Nullable String kind, @Nullable String text, @Nullable String context,
            @Nullable @Size(max = 8) List<String> technologies) {
    }
}
