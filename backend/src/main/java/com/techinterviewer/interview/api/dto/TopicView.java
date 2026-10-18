package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The topic a question is about.")
public record TopicView(
        @Schema(description = "Display name of the skill.", example = "Spring Boot") String skill,
        @Schema(description = "Display name of the topic.", example = "Transactions") String name) {
}
