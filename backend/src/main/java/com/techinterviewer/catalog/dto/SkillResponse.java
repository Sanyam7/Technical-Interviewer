package com.techinterviewer.catalog.dto;

import com.techinterviewer.catalog.SkillCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "An active skill of the catalog.")
public record SkillResponse(
        long id,
        String slug,
        String name,
        SkillCategory category,
        @Nullable String description) {
}
