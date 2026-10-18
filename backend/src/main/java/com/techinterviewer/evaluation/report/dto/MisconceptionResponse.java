package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A technically wrong statement in an answer, and what is true.")
public record MisconceptionResponse(
        @Schema(description = "What the candidate said", example = "volatile makes count++ atomic") String claim,
        @Schema(description = "What is true", example = "volatile guarantees visibility; count++ is still a read-modify-write race") String correction,
        @Schema(description = "Topic of the question; null for coding and design questions", nullable = true) @Nullable GapTopicResponse topic,
        @Schema(description = "The question it was said in") UUID questionId) {
}
