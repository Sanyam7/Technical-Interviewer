package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The approach and complexity of a solution, next to what the reference solution achieves. */
@Schema(description = "The approach and complexity of a solution as the evaluator read them, and the reference solution's complexity.")
public record CodeAnalysisFeedback(
        String approach,
        @Schema(example = "O(n^2)") String timeComplexity,
        @Schema(example = "O(1)") String spaceComplexity,
        @Schema(description = "The reference solution's time complexity; absent for a problem the AI wrote.", example = "O(n)", nullable = true)
        @Nullable String referenceTimeComplexity,
        @Schema(nullable = true, example = "O(n)") @Nullable String referenceSpaceComplexity) {
}
