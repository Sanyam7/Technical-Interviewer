package com.techinterviewer.ai.provider;

/**
 * The provider rejected the request itself (4xx other than 429): wrong model name, invalid schema, bad or missing API
 * key. Sending it again changes nothing, so it is never retried; it usually means the configuration is wrong.
 */
public final class InvalidRequestException extends LlmException {

    public InvalidRequestException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public String code() {
        return "INVALID_REQUEST";
    }
}
