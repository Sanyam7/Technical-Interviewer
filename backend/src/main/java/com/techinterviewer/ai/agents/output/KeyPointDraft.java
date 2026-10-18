package com.techinterviewer.ai.agents.output;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An expected key point as the model writes it: a short id unique within the question, the idea itself, and the
 * reference material (r1, r2, ...) it rests on, if any. Sources are absent in outputs of prompts without reference
 * material (version 1).
 */
public record KeyPointDraft(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{1,40}", message = "must be 1 to 40 letters, digits, '_', '.' or '-'") String id,
        @NotBlank @Size(max = 500) String text,
        @Nullable @Size(max = 3) List<@NotBlank @Size(max = 10) String> sources) {

    public KeyPointDraft(String id, String text) {
        this(id, text, null);
    }

    public List<String> sourceTokens() {
        return sources == null ? List.of() : sources;
    }
}
