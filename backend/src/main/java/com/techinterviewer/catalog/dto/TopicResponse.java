package com.techinterviewer.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "A topic of a skill with the difficulty range (1 easiest to 5 hardest) it is asked at.")
public record TopicResponse(
        String slug,
        String name,
        @Nullable String description,
        int minDifficulty,
        int maxDifficulty) {
}
