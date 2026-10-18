package com.techinterviewer.security;

import java.time.Duration;

/**
 * A freshly signed access token.
 *
 * @param value     the compact JWT, sent by the client as {@code Authorization: Bearer <value>}
 * @param expiresIn how long the token is valid, counted from its issue
 */
public record IssuedToken(String value, Duration expiresIn) {

    /** Seconds until expiry, as the API reports it ({@code expiresIn}). */
    public long expiresInSeconds() {
        return expiresIn.toSeconds();
    }
}
