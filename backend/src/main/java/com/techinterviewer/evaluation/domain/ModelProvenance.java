package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which prompt and model produced an evaluation, and which recorded model call it was.
 *
 * @param promptId      prompt identifier, at most 60 characters
 * @param promptVersion prompt version, at most 20 characters
 * @param model         model name, at most 80 characters
 * @param llmCallId     the recorded call; absent when the call log has no row for it
 */
public record ModelProvenance(String promptId, String promptVersion, String model, @Nullable UUID llmCallId) {

    public ModelProvenance {
        Require.notBlank(promptId, "promptId", 60);
        Require.notBlank(promptVersion, "promptVersion", 20);
        Require.notBlank(model, "model", 80);
    }
}
