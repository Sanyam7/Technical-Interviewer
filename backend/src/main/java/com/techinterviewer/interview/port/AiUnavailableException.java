package com.techinterviewer.interview.port;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;

/**
 * An AI port could not produce a result: no provider is configured, the provider is down, rate limited or timed
 * out, or it returned output that failed validation after every retry. Callers degrade as described in
 * AI_ARCHITECTURE.md (a pending evaluation, a retryable question, a report without narrative); where nothing can
 * degrade it reaches the client as {@code 503 AI_PROVIDER_UNAVAILABLE}.
 *
 * <p>The detail is returned to the client verbatim and must stay generic: provider names, keys and raw provider
 * messages belong in the cause only.
 */
public class AiUnavailableException extends ApiException {

    public static final String DEFAULT_DETAIL = "The AI service is temporarily unavailable. Try again shortly.";

    public AiUnavailableException() {
        super(ErrorCode.AI_PROVIDER_UNAVAILABLE, DEFAULT_DETAIL);
    }

    public AiUnavailableException(String detail) {
        super(ErrorCode.AI_PROVIDER_UNAVAILABLE, detail);
    }

    public AiUnavailableException(String detail, Throwable cause) {
        super(ErrorCode.AI_PROVIDER_UNAVAILABLE, detail, cause);
    }
}
