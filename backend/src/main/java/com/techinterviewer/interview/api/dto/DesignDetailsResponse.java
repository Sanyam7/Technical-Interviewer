package com.techinterviewer.interview.api.dto;

import com.techinterviewer.evaluation.domain.Criterion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The material that comes with a system design question.")
public record DesignDetailsResponse(
        @Schema(description = "Name of the problem, for a problem from the curated bank.", example = "Design a URL shortener", nullable = true)
        @Nullable String title,
        @Schema(description = "What the interviewer states up front: scale, latency, constraints. Empty for a follow-up.") List<String> facts,
        @Schema(description = "The design dimension a follow-up probes, chosen by the interview engine. Absent for the problem itself.",
                nullable = true, example = "CACHING")
        @Nullable Criterion probe) {

    public DesignDetailsResponse {
        facts = List.copyOf(facts);
    }
}
