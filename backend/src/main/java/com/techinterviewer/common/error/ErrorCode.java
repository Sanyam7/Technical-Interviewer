package com.techinterviewer.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable error identifiers. The enum name is the {@code code} property of a
 * problem document; its kebab-case form is the last segment of the problem {@code type} URL.
 */
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "Malformed request body"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token expired"),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "Illegal interview state transition"),
    QUESTION_NOT_CURRENT(HttpStatus.CONFLICT, "Question is not the current question"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    INTERVIEW_EXPIRED(HttpStatus.CONFLICT, "Interview time is up"),
    INTERVIEW_NOT_FINISHED(HttpStatus.CONFLICT, "Interview is not finished"),
    CONFIGURATION_IN_USE(HttpStatus.CONFLICT, "Configuration is in use"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    DAILY_INTERVIEW_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Daily interview limit reached"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Request body too large"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported file type"),
    DOCUMENT_UNREADABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Document cannot be read"),
    DOCUMENT_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "Document has no readable text"),
    DOCUMENT_TOO_LONG(HttpStatus.UNPROCESSABLE_CONTENT, "Document too long"),
    NOT_A_RESUME(HttpStatus.UNPROCESSABLE_CONTENT, "Not a résumé"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable"),
    CODE_EXECUTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Code execution unavailable"),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable");

    private static final String TYPE_BASE = "https://techinterviewer.dev/problems/";

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public String code() {
        return name();
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** Generic code for a status produced by the Spring MVC infrastructure. */
    static ErrorCode forStatus(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            default -> statusCode.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
