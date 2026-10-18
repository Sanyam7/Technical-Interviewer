package com.techinterviewer.ai.provider;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The provider answered 429: too many requests or the quota is exhausted. Retried, honouring the wait the provider asked for ({@code Retry-After}, or Gemini's {@code RetryInfo}). */
public final class RateLimitedException extends LlmException {

    private final @Nullable Duration retryAfter;

    public RateLimitedException(String message, @Nullable Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long the provider asked the caller to wait, if it said. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public String code() {
        return "RATE_LIMITED";
    }
}
