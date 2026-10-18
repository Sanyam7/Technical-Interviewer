package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "A worked example of a coding problem.")
public record CodingExampleView(String input, String output, @Nullable String explanation) {
}
