package com.techinterviewer.ai.provider;

/** The call did not complete within its time limit. Retryable while the call budget lasts. */
public final class LlmTimeoutException extends LlmException {

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmTimeoutException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public String code() {
        return "TIMEOUT";
    }
}
