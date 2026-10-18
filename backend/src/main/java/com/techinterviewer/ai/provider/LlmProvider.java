package com.techinterviewer.ai.provider;

/**
 * One large-language-model service (Gemini, Ollama). The rest of the application never sees a provider: the agents
 * talk to {@code StructuredOutputClient}, which picks a provider through the model router. A provider makes exactly
 * one HTTP call per {@link #generate} and does not retry; retries, the call budget and the call records belong to the
 * caller, so every attempt is visible.
 *
 * <p>Implementations translate their wire format into {@link LlmResponse} and their failures into the
 * {@link LlmException} subclasses, so callers never handle provider-specific HTTP details.
 */
public interface LlmProvider {

    /** Stable identifier used in routing and in {@code llm_calls.provider}: {@code gemini} or {@code ollama}. */
    String id();

    /**
     * Sends one request and waits for the complete response, at most {@link LlmRequest#timeout()}.
     *
     * @throws LlmException for every failure: rate limited, unavailable, timed out, rejected request, blocked content
     */
    LlmResponse generate(LlmRequest request);
}
