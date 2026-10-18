package com.techinterviewer.ai.provider;

/**
 * The provider could not be reached or failed on its side (connection refused, 5xx). Retryable unless the provider
 * answered in a way that will not change on a retry (an unexpected response shape).
 */
public final class ProviderUnavailableException extends LlmException {

    private final boolean retryable;

    public ProviderUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public ProviderUnavailableException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    @Override
    public boolean retryable() {
        return retryable;
    }

    @Override
    public String code() {
        return "PROVIDER_UNAVAILABLE";
    }
}
