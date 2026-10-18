package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The material that comes with a coding question.")
public record QuestionDetailsResponse(
        @Schema(description = "Language the answer must be written in.", example = "java", nullable = true) @Nullable String language,
        List<String> constraints,
        List<CodingExampleView> examples,
        @Schema(description = "Code skeleton for the editor.", nullable = true) @Nullable String starterCode,
        @Schema(description = "Name of the problem, for a problem from the curated bank.", example = "Two Sum", nullable = true) @Nullable String title,
        @Schema(description = "Whether the solution is run against tests: the examples can be run before submitting, and hidden tests "
                + "decide its correctness. False for a problem the AI wrote, a language without tests, or when execution is off.") boolean testable) {

    public QuestionDetailsResponse {
        constraints = List.copyOf(constraints);
        examples = List.copyOf(examples);
    }
}
