package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Creates an interview from a saved configuration or from an inline one: exactly one of the two must be present. */
@Schema(
        description = "Exactly one of configurationId (a configuration of the caller) or configuration (saved as a new "
                + "configuration of the caller) must be present.",
        example = "{\"configuration\": {\"role\": \"BACKEND\", \"experienceLevel\": \"MID\", \"difficulty\": \"MEDIUM\", "
                + "\"durationMinutes\": 45, \"interviewType\": \"FULL_LOOP\", \"skills\": [\"java\", \"spring-boot\"], "
                + "\"codingLanguage\": \"java\"}}")
public record CreateInterviewRequest(
        @Schema(description = "Id of one of the caller's configurations.", nullable = true)
        @Nullable UUID configurationId,
        @Schema(description = "A configuration to validate, save and use.", nullable = true)
        @Valid @Nullable ConfigurationRequest configuration) {
}
