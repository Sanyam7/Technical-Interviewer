package com.techinterviewer.security;

import com.techinterviewer.config.AppProperties;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HS256 signing key of access tokens, taken from {@code app.security.jwt.secret} ({@code JWT_SECRET}): base64
 * of at least 32 random bytes. The application <b>refuses to start</b> without a usable secret
 * ({@link MissingJwtSecretException}, explained by {@link MissingJwtSecretFailureAnalyzer}), with one exception:
 * when {@code allow-ephemeral-secret} is on (the {@code local} profile) a random 48-byte key is generated and a
 * warning is logged. Such tokens die with the process.
 */
public final class JwtKeyProvider {

    /** HMAC-SHA-256 needs a key of at least its output size (RFC 7518 section 3.2). */
    static final int MIN_KEY_BYTES = 32;
    /** A random key has about 30 distinct byte values in 32 bytes; fewer than this means a placeholder, not a secret. */
    static final int MIN_DISTINCT_BYTE_VALUES = 8;
    static final int EPHEMERAL_KEY_BYTES = 48;

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    private final SecretKey key;
    private final boolean ephemeral;

    private JwtKeyProvider(SecretKey key, boolean ephemeral) {
        this.key = key;
        this.ephemeral = ephemeral;
    }

    /** @throws MissingJwtSecretException when there is no usable secret and an ephemeral one is not allowed */
    public static JwtKeyProvider from(AppProperties.Jwt properties) {
        byte[] configured = configuredKey(properties);
        if (configured != null) {
            return new JwtKeyProvider(hmacKey(configured), false);
        }
        byte[] bytes = new byte[EPHEMERAL_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        log.warn("JWT_SECRET is not set: generated a random signing key for this process. Access tokens will not "
                + "survive a restart. This is only acceptable for local development.");
        return new JwtKeyProvider(hmacKey(bytes), true);
    }

    /**
     * Checks the configuration without creating a key, so the startup can fail before any other bean (the
     * database migrations included) has done work.
     *
     * @throws MissingJwtSecretException when there is no usable secret and an ephemeral one is not allowed
     */
    static void validate(AppProperties.Jwt properties) {
        configuredKey(properties);
    }

    /** The decoded secret, or null when none is configured and an ephemeral key is allowed. */
    private static byte[] configuredKey(AppProperties.Jwt properties) {
        String secret = properties.secret();
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            if (!properties.allowEphemeralSecret()) {
                throw new MissingJwtSecretException("is not set");
            }
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException ex) {
            throw new MissingJwtSecretException("is not valid base64");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new MissingJwtSecretException(
                    "decodes to " + bytes.length + " bytes but at least " + MIN_KEY_BYTES + " are required");
        }
        if (distinctBytes(bytes) < MIN_DISTINCT_BYTE_VALUES) {
            throw new MissingJwtSecretException("has hardly any variation (a placeholder such as all zeros?); generate one with openssl rand -base64 48");
        }
        return bytes;
    }

    private static long distinctBytes(byte[] bytes) {
        boolean[] seen = new boolean[256];
        long distinct = 0;
        for (byte value : bytes) {
            if (!seen[value & 0xFF]) {
                seen[value & 0xFF] = true;
                distinct++;
            }
        }
        return distinct;
    }

    /** A provider around a key built elsewhere (tests that need a second, different key). */
    public static JwtKeyProvider ofKey(byte[] keyBytes) {
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("a signing key needs at least " + MIN_KEY_BYTES + " bytes");
        }
        return new JwtKeyProvider(hmacKey(keyBytes), false);
    }

    private static SecretKey hmacKey(byte[] bytes) {
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    public SecretKey signingKey() {
        return key;
    }

    /** True when the key was generated at startup instead of configured. */
    public boolean isEphemeral() {
        return ephemeral;
    }
}
