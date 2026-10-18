package com.techinterviewer.ai.agents.output;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The candidate-visible material of a coding problem. */
public record CodingDraft(
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> constraints,
        @NotNull @Size(min = 1, max = 5) List<@NotNull @Valid ExampleDraft> examples,
        @Size(max = 2000) @Nullable String starterSignature) {

    /** A worked example: input, expected output, optional explanation. */
    public record ExampleDraft(
            @NotBlank @Size(max = 1000) String input,
            @NotBlank @Size(max = 1000) String output,
            @Size(max = 1000) @Nullable String explanation) {
    }
}
