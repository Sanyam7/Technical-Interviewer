package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A newly created interview: its configuration and plan. Questions come after it was started.")
public record InterviewResponse(
        UUID id,
        InterviewState state,
        ConfigurationResponse configuration,
        PublicPlan plan,
        Instant createdAt,
        @Schema(description = "Increases with every change of the interview.") long version) {
}
