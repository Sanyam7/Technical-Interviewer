package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;

/** The optional body of a skip: only how long the candidate looked at the question. */
@Schema(description = "Optional body of a skip.", example = "{\"timeTakenSeconds\": 12}")
public record SkipRequest(
        @Schema(description = "How long the candidate looked at the question, as the client measured it.", nullable = true)
        @Min(0) @Max(AnswerRequest.MAX_TIME_TAKEN_SECONDS) @Nullable Integer timeTakenSeconds) {
}
