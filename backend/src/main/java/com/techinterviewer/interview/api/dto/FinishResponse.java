package com.techinterviewer.interview.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The outcome of finishing an interview: the report is ready (COMPLETED), or the attempt can be repeated. */
@Schema(description = "The state after the final evaluation was attempted.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinishResponse(
        @Schema(description = "COMPLETED when the report is ready, otherwise FINAL_EVALUATION.") InterviewState state,
        @Schema(description = "READY, or FAILED when the report could not be completed (for example answers still await their evaluation).")
        ReportStatus reportStatus,
        @Schema(description = "Repeating POST /finish or POST /report/retry can still produce the report.") boolean retryable,
        @Schema(description = "Where to read the report; present when it is ready.", nullable = true) @Nullable String reportUrl) {
}
