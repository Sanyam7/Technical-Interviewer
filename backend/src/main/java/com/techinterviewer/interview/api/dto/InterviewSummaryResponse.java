package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "One line of the list of the caller's interviews.")
public record InterviewSummaryResponse(
        UUID id,
        InterviewState state,
        ConfigurationResponse configuration,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Schema(description = "When the question phase ended (time up, finished, abandoned).", nullable = true) @Nullable Instant endedAt,
        @Nullable EndReason endReason) {
}
