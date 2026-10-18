package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestAppProperties;
import com.techinterviewer.TestClock;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.Role;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** The decoder in isolation: which tokens it accepts and which it rejects, and on which exact condition. */
class AccessTokenDecoderTest {

    private static final UUID USER = UUID.randomUUID();

    private final TestClock clock = new TestClock();
    private final String secret = randomSecret();
    private final AppProperties properties = TestAppProperties.withJwt(secret, false, "tech-interviewer", Duration.ofMinutes(15));
    private final JwtKeyProvider keys = JwtKeyProvider.from(properties.security().jwt());
    private final TestAuth auth = new TestAuth(keys, Base64.getDecoder().decode(secret), properties, clock);
    private final JwtDecoder decoder = AccessTokenDecoder.create(keys.signingKey(), "tech-interviewer", clock);

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void aFreshTokenForEachRoleIsAccepted() {
        for (Role role : Role.values()) {
            Jwt jwt = decoder.decode(auth.token(USER, role));

            assertThat(jwt.getSubject()).isEqualTo(USER.toString());
            assertThat(jwt.getClaimAsString("role")).isEqualTo(role.name());
            assertThat(jwt.getClaimAsString("iss")).isEqualTo("tech-interviewer");
        }
    }

    static Stream<Arguments> rejectedTokens() {
        UUID id = USER;
        return Stream.of(
                Arguments.of("alg none", (Function<TestAuth, String>) a -> a.algNoneToken(id, Role.ADMIN)),
                Arguments.of("RS256", (Function<TestAuth, String>) a -> a.rs256Token(id, Role.ADMIN)),
                Arguments.of("HS384", (Function<TestAuth, String>) a -> a.hs384Token(id, Role.ADMIN)),
                Arguments.of("another issuer", (Function<TestAuth, String>) a -> a.otherIssuerToken(id, Role.ADMIN)),
                Arguments.of("another key", (Function<TestAuth, String>) a -> a.otherKeyToken(id, Role.ADMIN)),
                Arguments.of("tampered signature", (Function<TestAuth, String>) a -> a.tamperedSignatureToken(id, Role.ADMIN)),
                Arguments.of("edited payload", (Function<TestAuth, String>) a -> a.roleEscalatedToken(id)),
                Arguments.of("expired", (Function<TestAuth, String>) a -> a.expiredToken(id, Role.ADMIN)),
                Arguments.of("no role", (Function<TestAuth, String>) a -> a.withoutRoleToken(id)),
                Arguments.of("unknown role", (Function<TestAuth, String>) a -> a.withRoleClaimToken(id, "ROOT")),
                Arguments.of("no subject", (Function<TestAuth, String>) a -> a.withoutSubjectToken(Role.ADMIN)),
                Arguments.of("subject not a uuid", (Function<TestAuth, String>) a -> a.withSubjectToken("1", Role.ADMIN)),
                Arguments.of("no expiry", (Function<TestAuth, String>) a -> a.withoutExpiryToken(id, Role.ADMIN)),
                Arguments.of("garbage", (Function<TestAuth, String>) a -> "garbage"),
                Arguments.of("empty", (Function<TestAuth, String>) a -> ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    void everyForgedOrMalformedTokenIsRejected(String name, Function<TestAuth, String> token) {
        assertThatThrownBy(() -> decoder.decode(token.apply(auth))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokenIsValidUpToButNotIncludingItsExpiryInstant() {
        String token = auth.token(USER, Role.CANDIDATE);

        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        assertThat(decoder.decode(token).getSubject()).isEqualTo(USER.toString());

        clock.advance(Duration.ofMillis(1));
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .satisfies(error -> assertThat(((JwtValidationException) error).getErrors())
                        .extracting(e -> e.getErrorCode()).containsExactly(AccessTokenClaimsValidator.EXPIRED));
    }

    @Test
    void thereIsNoClockSkewAllowanceForAnExpiredToken() {
        String token = auth.token(USER, Role.CANDIDATE);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void aTokenIsOnlyAcceptedForTheConfiguredIssuer() {
        JwtDecoder otherIssuer = AccessTokenDecoder.create(keys.signingKey(), "someone-else", clock);

        assertThatThrownBy(() -> otherIssuer.decode(auth.token(USER, Role.CANDIDATE))).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void aTokenWithoutAnIssuerIsRejected() {
        String noIssuer = auth.custom(USER, Role.CANDIDATE, claims -> claims.issuer(null));

        assertThatThrownBy(() -> decoder.decode(noIssuer)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void extraClaimsDoNotMatterButTheRequiredOnesDo() {
        String withExtras = auth.custom(USER, Role.CANDIDATE, claims -> claims.claim("scope", "admin").claim("extra", "x"));

        assertThat(decoder.decode(withExtras).getSubject()).isEqualTo(USER.toString());
    }

    @Test
    void aTokenIssuedInTheFutureButNotYetExpiredIsStillJudgedByExpiryOnly() {
        String token = auth.custom(USER, Role.CANDIDATE, claims -> claims.expirationTime(Date.from(clock.instant().plusSeconds(1))));

        assertThat(decoder.decode(token)).isNotNull();
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void aTokenThatIsNotValidYetIsRejectedAndAcceptedFromItsNotBeforeInstant() {
        Date notBefore = Date.from(clock.instant().plusSeconds(60));
        String token = auth.custom(USER, Role.CANDIDATE, claims -> claims.notBeforeTime(notBefore));

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .satisfies(error -> assertThat(((JwtValidationException) error).getErrors())
                        .extracting(e -> e.getErrorCode()).containsExactly("invalid_token"));

        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);

        clock.advance(Duration.ofSeconds(1));
        assertThat(decoder.decode(token).getSubject()).isEqualTo(USER.toString());
    }

    @Test
    void aTokenWithANotBeforeInThePastIsAccepted() {
        Date notBefore = Date.from(clock.instant().minusSeconds(30));

        assertThat(decoder.decode(auth.custom(USER, Role.CANDIDATE, claims -> claims.notBeforeTime(notBefore))).getSubject())
                .isEqualTo(USER.toString());
    }
}
