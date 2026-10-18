package com.techinterviewer.ai.provider;

import com.techinterviewer.common.validation.Require;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One generation request, independent of the provider.
 *
 * @param model          provider model name, for example {@code gemini-3.8-flash}
 * @param systemPrompt   instructions: role, rules, rubric, output semantics
 * @param userPrompt     the task with its data; untrusted text in it is already delimited by the prompt renderer
 * @param options        sampling and thinking settings
 * @param responseSchema JSON Schema the output must follow; the provider enforces it natively (constrained decoding).
 *                       Null asks for free text, which no agent does today
 * @param timeout        the longest this one call may take, connection included; the caller derives it from what is
 *                       left of the operation's call budget
 */
public record LlmRequest(
        String model,
        String systemPrompt,
        String userPrompt,
        GenerationOptions options,
        @Nullable JsonNode responseSchema,
        Duration timeout) {

    public LlmRequest {
        Require.notBlank(model, "model", 80);
        Require.notBlank(systemPrompt, "systemPrompt");
        Require.notBlank(userPrompt, "userPrompt");
        Require.nonNull(options, "options");
        Require.nonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive but was " + timeout);
        }
    }

    /** The same request with a shorter time limit, for a retry late in the call budget. */
    public LlmRequest withTimeout(Duration shorter) {
        return new LlmRequest(model, systemPrompt, userPrompt, options, responseSchema, shorter);
    }

    /** The same request with another user prompt, for the repair attempt. */
    public LlmRequest withUserPrompt(String prompt) {
        return new LlmRequest(model, systemPrompt, prompt, options, responseSchema, timeout);
    }
}
