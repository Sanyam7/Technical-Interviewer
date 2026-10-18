package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.evaluation.domain.KeyPointStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A concept a good answer contains that the answer missed (MISSING) or covered only partly (PARTIAL).")
public record MissedConceptResponse(
        @Schema(description = "The concept", example = "volatile gives visibility, not atomicity of read-modify-write") String concept,
        @Schema(description = "MISSING or PARTIAL") KeyPointStatus status,
        @Schema(description = "Topic of the question; null for coding and design questions", nullable = true) @Nullable GapTopicResponse topic,
        @Schema(description = "The question it was missed in") UUID questionId) {
}
