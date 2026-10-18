package com.techinterviewer.security;

import com.techinterviewer.user.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Names of the claims of an access token and their strict reading. */
final class AccessTokenClaims {

    static final String ROLE = "role";

    private AccessTokenClaims() {
    }

    /** The account of a token, or empty when {@code sub} is not a UUID or {@code role} is not a known role. */
    static Optional<AuthenticatedUser> user(Jwt jwt) {
        String subject = jwt.getSubject();
        String role = jwt.getClaimAsString(ROLE);
        if (subject == null || role == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthenticatedUser(UUID.fromString(subject), Role.valueOf(role)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
