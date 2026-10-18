package com.techinterviewer.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.techinterviewer.TestAppProperties;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.Role;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Real, signed bearer tokens for tests, in every flavour an attacker or a stale client could send.
 *
 * <p>Valid tokens are produced by the application's own {@link JwtService}, so they carry exactly the claims
 * production tokens carry. In a Spring test use {@link #forContext(ApplicationContext)} (same key, issuer and
 * clock as the application under test) or the static {@link #bearer(UUID, Role)} when the test properties' secret
 * is in effect, which is the default for every test context:
 *
 * <pre>
 * mockMvc.perform(get("/api/v1/interviews").with(TestAuth.bearer(userId, Role.CANDIDATE)))
 * </pre>
 *
 * The signing secret of the tests is read from {@code src/test/resources/application.properties}.
 */
public final class TestAuth {

    public static final String ISSUER = "tech-interviewer";

    private final JwtKeyProvider keys;
    private final byte[] secretBytes;
    private final AppProperties properties;
    private final Clock clock;

    public TestAuth(JwtKeyProvider keys, byte[] secretBytes, AppProperties properties, Clock clock) {
        this.keys = keys;
        this.secretBytes = secretBytes;
        this.properties = properties;
        this.clock = clock;
    }

    /** Uses the key, issuer and clock of the application context. */
    public static TestAuth forContext(ApplicationContext context) {
        JwtKeyProvider keys = context.getBean(JwtKeyProvider.class);
        return new TestAuth(keys, keys.signingKey().getEncoded(), context.getBean(AppProperties.class), context.getBean(Clock.class));
    }

    /** Uses the test properties' secret, the default issuer and the system clock. */
    public static TestAuth standalone() {
        String secret = testSecret();
        AppProperties properties = TestAppProperties.withJwt(secret, false, ISSUER, Duration.ofMinutes(15));
        JwtKeyProvider keys = JwtKeyProvider.from(properties.security().jwt());
        return new TestAuth(keys, Base64.getDecoder().decode(secret), properties, Clock.systemUTC());
    }

    /** {@code Authorization: Bearer <valid token>} for a MockMvc request, signed with the test secret. */
    public static RequestPostProcessor bearer(UUID userId, Role role) {
        return bearerToken(standalone().token(userId, role));
    }

    public static RequestPostProcessor bearerToken(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    public static String testSecret() {
        Properties properties = new Properties();
        try (InputStream in = TestAuth.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return properties.getProperty("app.security.jwt.secret");
    }

    public RequestPostProcessor asUser(UUID userId, Role role) {
        return bearerToken(token(userId, role));
    }

    // ---- valid ------------------------------------------------------------------------------------------

    public String token(UUID userId, Role role) {
        return new JwtService(keys, properties, clock).issueAccessToken(new AuthenticatedUser(userId, role)).value();
    }

    // ---- invalid, each in exactly one way ---------------------------------------------------------------

    /** Correctly signed and issued, but its {@code exp} was two hours ago. */
    public String expiredToken(UUID userId, Role role) {
        Clock past = Clock.fixed(clock.instant().minus(Duration.ofHours(3)), clock.getZone());
        return new JwtService(keys, properties, past).issueAccessToken(new AuthenticatedUser(userId, role)).value();
    }

    /** A valid CANDIDATE token whose payload was edited to ADMIN without re-signing. */
    public String roleEscalatedToken(UUID userId) {
        String[] parts = token(userId, Role.CANDIDATE).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        if (!payload.contains("\"CANDIDATE\"")) {
            throw new IllegalStateException("unexpected payload " + payload);
        }
        String edited = payload.replace("\"CANDIDATE\"", "\"ADMIN\"");
        return parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(edited.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
    }

    /**
     * A valid token with one character of the signature changed. A character in the middle is changed: the last
     * character of a base64url encoded 32-byte signature carries only four significant bits, so editing it can
     * leave the decoded signature unchanged.
     */
    public String tamperedSignatureToken(UUID userId, Role role) {
        String token = token(userId, role);
        int position = token.lastIndexOf('.') + 10;
        char original = token.charAt(position);
        return token.substring(0, position) + (original == 'A' ? 'B' : 'A') + token.substring(position + 1);
    }

    /** Signed with the right key but issued by someone else. */
    public String otherIssuerToken(UUID userId, Role role) {
        return signed(builder(userId, role).issuer("https://evil.example"), JWSAlgorithm.HS256);
    }

    /** Well formed, HS256, claims fine, but signed with a key the server does not know. */
    public String otherKeyToken(UUID userId, Role role) {
        JwtKeyProvider otherKey = JwtKeyProvider.ofKey(Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4v"));
        return new JwtService(otherKey, properties, clock).issueAccessToken(new AuthenticatedUser(userId, role)).value();
    }

    /** {@code alg: none}: an unsigned token that claims to be anybody. */
    public String algNoneToken(UUID userId, Role role) {
        return new PlainJWT(builder(userId, role).build()).serialize();
    }

    /** Signed with RS256 (an asymmetric key of the attacker's own). */
    public String rs256Token(UUID userId, Role role) {
        try {
            RSAKey key = new RSAKeyGenerator(2048).generate();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder(userId, role).build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Signed with HS384 using the correct key: only HS256 is accepted. */
    public String hs384Token(UUID userId, Role role) {
        return signed(builder(userId, role), JWSAlgorithm.HS384);
    }

    public String withoutRoleToken(UUID userId) {
        return signed(builder(userId, Role.CANDIDATE).claim("role", null), JWSAlgorithm.HS256);
    }

    public String withoutSubjectToken(Role role) {
        return signed(builder(UUID.randomUUID(), role).subject(null), JWSAlgorithm.HS256);
    }

    public String withoutExpiryToken(UUID userId, Role role) {
        return signed(builder(userId, role).expirationTime(null), JWSAlgorithm.HS256);
    }

    public String withRoleClaimToken(UUID userId, String role) {
        return signed(builder(userId, Role.CANDIDATE).claim("role", role), JWSAlgorithm.HS256);
    }

    public String withSubjectToken(String subject, Role role) {
        return signed(builder(UUID.randomUUID(), role).subject(subject), JWSAlgorithm.HS256);
    }

    /** Signed with the right key and algorithm, with the claims of a valid token adjusted by the caller. */
    public String custom(UUID userId, Role role, Consumer<JWTClaimsSet.Builder> adjust) {
        JWTClaimsSet.Builder builder = builder(userId, role);
        adjust.accept(builder);
        return signed(builder, JWSAlgorithm.HS256);
    }

    private JWTClaimsSet.Builder builder(UUID userId, Role role) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(properties.security().jwt().issuer())
                .subject(userId.toString())
                .claim("role", role.name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .jwtID(UUID.randomUUID().toString());
    }

    private String signed(JWTClaimsSet.Builder claims, JWSAlgorithm algorithm) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims.build());
            jwt.sign(new MACSigner(secretBytes));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
