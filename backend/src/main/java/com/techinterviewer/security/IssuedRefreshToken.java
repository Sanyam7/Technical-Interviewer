package com.techinterviewer.security;

import java.time.Instant;

/**
 * A refresh token as handed to the client: the secret value (shown exactly once, never stored) and when it expires.
 */
record IssuedRefreshToken(String value, Instant expiresAt) {

    /** The secret never appears in a log line. */
    @Override
    public String toString() {
        return "IssuedRefreshToken[value=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
