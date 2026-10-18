package com.techinterviewer.security;

import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates the claims of an access token that Spring's default validators let through: {@code exp} must be
 * present and in the future and {@code nbf}, when present, must not be in the future (no clock skew allowance:
 * this application issues and checks its own tokens), and
 * {@code sub} and {@code role} must name a real account (a UUID) and a known role.
 *
 * <p>An expired token is reported with the dedicated error code {@link #EXPIRED}, so the authentication entry
 * point can tell the client to refresh instead of log in again.
 */
final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    static final String EXPIRED = "token_expired";
    private static final String INVALID = "invalid_token";

    private final Clock clock;

    AccessTokenClaimsValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            return failure(INVALID, "The token has no expiry.");
        }
        if (!clock.instant().isBefore(expiresAt)) {
            return failure(EXPIRED, "The token has expired.");
        }
        Instant notBefore = jwt.getNotBefore();
        if (notBefore != null && clock.instant().isBefore(notBefore)) {
            return failure(INVALID, "The token is not valid yet.");
        }
        if (AccessTokenClaims.user(jwt).isEmpty()) {
            return failure(INVALID, "The token does not identify an account.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
