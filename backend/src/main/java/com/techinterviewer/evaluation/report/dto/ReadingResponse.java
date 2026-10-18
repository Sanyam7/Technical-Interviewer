package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A knowledge-base section to read, cited by the study plan. */
@Schema(description = "A knowledge-base section the study plan points to")
public record ReadingResponse(
        @Schema(example = "Java concurrency") String title,
        @Schema(description = "Where in the document", example = "Java concurrency › The Java memory model and volatile", nullable = true)
        @Nullable String section,
        @Schema(description = "The document's path in the knowledge base", example = "java/concurrency.md", nullable = true)
        @Nullable String source) {
}
