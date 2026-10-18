package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One test as the candidate sees it. Input and expected output are present for the visible examples only: a hidden test is
 * shown by name and category, never by its data.
 */
@Schema(description = "One test. input and expected are present only for the visible examples.")
public record CodeTestResultView(
        String name,
        @Schema(allowableValues = {"EXAMPLE", "GENERAL", "EDGE", "PERFORMANCE"}) String category,
        @Schema(allowableValues = {"PASSED", "FAILED", "ERROR", "TIMEOUT", "NOT_RUN"}) String status,
        @Schema(nullable = true) @Nullable String input,
        @Schema(nullable = true) @Nullable String expected,
        @Schema(nullable = true, description = "What the code returned (visible examples only)") @Nullable String actual,
        @Schema(nullable = true) @Nullable String message,
        @Schema(nullable = true) @Nullable Long timeMs) {
}
