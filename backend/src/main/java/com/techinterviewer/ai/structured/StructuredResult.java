package com.techinterviewer.ai.structured;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An accepted, validated output with its provenance.
 *
 * @param value         the output
 * @param model         the model that produced it
 * @param promptId      the prompt template
 * @param promptVersion its version
 * @param llmCallId     the recorded call that produced the accepted output; null only when recording failed (the
 *                      result is still valid, it just cannot be linked)
 * @param modelCalls    how many model calls the operation made, failed ones included
 * @param <T>           the output type
 */
public record StructuredResult<T>(
        T value, String model, String promptId, String promptVersion, @Nullable UUID llmCallId, int modelCalls) {
}
