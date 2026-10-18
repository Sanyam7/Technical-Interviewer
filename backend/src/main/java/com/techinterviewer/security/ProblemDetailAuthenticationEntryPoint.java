package com.techinterviewer.security;

import com.techinterviewer.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers requests that need authentication but carry none, or carry an access token that is not valid, with a
 * 401 problem document. A token that is well formed and correctly signed but expired is reported as
 * {@code TOKEN_EXPIRED} (the client should refresh); everything else, whatever is wrong with the token, is the
 * same generic {@code UNAUTHENTICATED}. The reason of a rejection is logged by Spring Security, never returned.
 *
 * <p>{@code WWW-Authenticate} follows RFC 6750: a bare {@code Bearer} challenge when no token was sent, and
 * {@code error="invalid_token"} when one was.
 */
@Component
class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String EXPIRED_DETAIL = "The access token has expired. Refresh it and retry.";
    static final String GENERIC_DETAIL = "Authentication is required to access this resource.";

    private final ProblemResponseWriter writer;

    ProblemDetailAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (isExpiredToken(authException)) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"The access token expired\"");
            writer.write(request, response, ErrorCode.TOKEN_EXPIRED, EXPIRED_DETAIL);
            return;
        }
        boolean tokenSent = authException instanceof OAuth2AuthenticationException;
        response.setHeader("WWW-Authenticate", tokenSent ? "Bearer error=\"invalid_token\"" : "Bearer");
        writer.write(request, response, ErrorCode.UNAUTHENTICATED, GENERIC_DETAIL);
    }

    /** True only when expiry is the sole reason the token was rejected. */
    private static boolean isExpiredToken(AuthenticationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation) {
                return validation.getErrors().stream()
                        .allMatch(error -> AccessTokenClaimsValidator.EXPIRED.equals(error.getErrorCode()));
            }
        }
        return false;
    }
}
