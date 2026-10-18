package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The result of running a draft against the visible examples.
 *
 * @param status        COMPLETED, COMPILATION_ERROR, TIMED_OUT, CRASHED or OUTPUT_LIMIT
 * @param passed        examples whose output was right
 * @param total         examples run
 * @param results       one per example, with its input, the expected output and what the code returned
 * @param compileOutput the compiler's message when the code did not compile
 * @param output        what the code printed itself (debugging output), shortened
 * @param durationMs    wall-clock time of the run, container start included
 */
@Schema(description = "The result of running a draft against the visible examples")
public record CodeRunResponse(
        @Schema(allowableValues = {"COMPLETED", "COMPILATION_ERROR", "TIMED_OUT", "CRASHED", "OUTPUT_LIMIT"}) String status,
        int passed,
        int total,
        List<CodeTestResultView> results,
        @Schema(nullable = true) @Nullable String compileOutput,
        String output,
        long durationMs) {
}
