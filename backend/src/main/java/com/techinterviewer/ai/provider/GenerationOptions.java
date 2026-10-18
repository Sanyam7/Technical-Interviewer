package com.techinterviewer.ai.provider;

import com.techinterviewer.common.validation.Require;

/**
 * Sampling settings of one task, taken from the routing configuration (never hard-coded in an agent).
 *
 * @param temperature     0 (deterministic) to 2
 * @param maxOutputTokens upper bound on the tokens of the answer; on Gemini 3 thinking tokens count towards it
 * @param thinking        how much internal reasoning the model may spend before answering
 */
public record GenerationOptions(double temperature, int maxOutputTokens, ThinkingLevel thinking) {

    public GenerationOptions {
        Require.between(temperature, 0, 2, "temperature");
        Require.between(maxOutputTokens, 1, 65_536, "maxOutputTokens");
        Require.nonNull(thinking, "thinking");
    }
}
