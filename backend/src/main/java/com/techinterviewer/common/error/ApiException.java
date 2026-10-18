package com.techinterviewer.common.error;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Domain/application failure that maps to a well-defined problem response. The detail is returned
 * to the client verbatim, so it must never contain internal information.
 *
 * <p>A failure can add members to the problem document ({@link #withProperty}, for example the
 * {@code currentState} of an interview in a 409) and response headers ({@link #withHeader},
 * {@link #withRetryAfter}). Both are public: nothing sensitive may be put in them.
 */
public class ApiException extends RuntimeException {

    /** Members every problem document already has; an extension cannot replace them. */
    private static final Set<String> RESERVED = Set.of("type", "title", "status", "detail", "instance", "code", "traceId", "errors");

    private final ErrorCode errorCode;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Adds a member to the problem document. */
    public ApiException withProperty(String name, Object value) {
        if (RESERVED.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is a standard problem member and cannot be overridden");
        }
        properties.put(name, value);
        return this;
    }

    public ApiException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** Sets {@code Retry-After} in whole seconds, rounded up and at least 1. */
    public ApiException withRetryAfter(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return withHeader("Retry-After", Long.toString(seconds));
    }

    /** Extra problem members, in insertion order. */
    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /** Extra response headers, in insertion order. */
    public Map<String, String> headers() {
        return Collections.unmodifiableMap(headers);
    }
}
