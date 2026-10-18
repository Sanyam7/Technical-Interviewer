package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(description = "What the candidate answered.")
public record AnswerView(
        @Schema(description = "The answer text or code; empty for a skipped question.") String content,
        boolean skipped,
        Instant submittedAt,
        @Schema(description = "The architecture diagram submitted with a system design answer; absent otherwise.", nullable = true)
        @Nullable ArchitectureView architecture) {
}
