package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The tests of a submitted solution, shown once the interview is completed. Hidden tests are listed by name, category and
 * result only: their inputs stay private to the problem bank.
 */
@Schema(description = "The tests of a submitted solution. Hidden tests show their name, category and result, not their data.")
public record CodeTestsFeedback(
        @Schema(allowableValues = {"COMPLETED", "COMPILATION_ERROR", "TIMED_OUT", "CRASHED", "OUTPUT_LIMIT"}) String status,
        String language,
        int passed,
        int total,
        List<CodeTestResultView> results,
        @Schema(nullable = true) @Nullable String compileOutput,
        long durationMs) {

    public CodeTestsFeedback {
        results = List.copyOf(results);
    }
}
