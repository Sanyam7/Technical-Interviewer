package com.techinterviewer.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Generation and storage form of opaque bearer secrets such as the refresh token. */
final class OpaqueTokens {

    /** 256 bits. */
    static final int BYTES = 32;

    private OpaqueTokens() {
    }

    /** A new random value: 32 bytes from the given source, base64url without padding (43 characters). */
    static String generate(SecureRandom random) {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What the database stores of a value: its SHA-256 as 64 lower-case hex characters. */
    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is available on every Java platform", ex);
        }
    }
}
