package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A catalog topic.")
public record GapTopicResponse(
        @Schema(description = "skill/topic key", example = "java/concurrency") String key,
        @Schema(description = "Display name", example = "Java / Concurrency") String name) {
}
