package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The share of the technical questions each stage gets, in whole percent that add up to 100: fundamentals (core "
        + "concepts and internals of each technology), practical (scenarios and debugging) and project (the candidate's own experience).")
public record StageMixView(
        @Schema(example = "60") @NotNull @Min(0) @Max(100) Integer fundamentals,
        @Schema(example = "25") @NotNull @Min(0) @Max(100) Integer practical,
        @Schema(example = "15") @NotNull @Min(0) @Max(100) Integer project) {
}
