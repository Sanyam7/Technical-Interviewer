package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Ends or closes an interview.", example = "{\"reason\": \"CANDIDATE_ENDED\"}")
public record FinishRequest(
        @Schema(description = "CANDIDATE_ENDED ends a running interview early; ACKNOWLEDGE_COMPLETE is valid only once the question "
                + "phase is over (FINAL_EVALUATION) or the interview is COMPLETED.")
        @NotNull FinishReason reason) {
}
