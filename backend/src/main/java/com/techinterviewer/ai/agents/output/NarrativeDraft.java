package com.techinterviewer.ai.agents.output;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The report narrative as the model writes it ({@code schemas/report-narrative.v1.json}). Evidence is cited by the
 * short references the prompt lists ({@code e1}, {@code e2}, ...), narrowed by an {@code enum} in the schema and
 * mapped back to evaluation ids by the agent.
 */
public record NarrativeDraft(
        @NotBlank @Size(max = 2000) String summary,
        @NotNull @Size(max = 10) List<@NotNull @Valid Item> strengths,
        @NotNull @Size(max = 10) List<@NotNull @Valid Item> improvementAreas,
        @NotNull @Size(max = 10) List<@NotBlank @Size(max = 500) String> studyPlan,
        @Nullable @Size(max = 5) List<@NotBlank @Size(max = 10) String> furtherReading) {

    public NarrativeDraft(String summary, List<Item> strengths, List<Item> improvementAreas, List<String> studyPlan) {
        this(summary, strengths, improvementAreas, studyPlan, null);
    }

    /** The reference material (r1, r2, ...) recommended for the study plan; empty without reference material. */
    public List<String> readingTokens() {
        return furtherReading == null ? List.of() : furtherReading;
    }

    /** A strength or an improvement area and the evaluations that show it. */
    public record Item(
            @NotBlank @Size(max = 1000) String text,
            @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 10) String> evidence) {
    }
}
