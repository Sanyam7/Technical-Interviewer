package com.techinterviewer.ai.provider;

/**
 * Reasoning effort of a model that "thinks" before it answers. Providers map it to their own setting: Gemini 3 to
 * {@code thinkingConfig.thinkingLevel} (which cannot switch thinking off, so {@link #OFF} is rejected for Gemini at
 * startup), Ollama to the {@code think} flag.
 */
public enum ThinkingLevel {
    /** Send nothing: the model's own default. */
    PROVIDER_DEFAULT,
    /** No thinking at all (Ollama only). */
    OFF,
    LOW,
    MEDIUM,
    HIGH
}
