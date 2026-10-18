package com.techinterviewer.ai.provider;

/**
 * A provider call failed. The subclasses are the only failures callers distinguish; provider-specific details (HTTP
 * status, provider message) are in the message and must never reach a client. The message is stored, truncated, in
 * {@code llm_calls.error_message}, so it must not contain secrets: providers never put the API key into a URL or a
 * message.
 */
public abstract sealed class LlmException extends RuntimeException
        permits RateLimitedException, ProviderUnavailableException, LlmTimeoutException, InvalidRequestException,
                ContentBlockedException {

    protected LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    protected LlmException(String message) {
        super(message);
    }

    /** Whether the same request may succeed when sent again (rate limits, outages, timeouts). */
    public abstract boolean retryable();

    /** Short code stored in {@code llm_calls.error_code}. */
    public abstract String code();
}
