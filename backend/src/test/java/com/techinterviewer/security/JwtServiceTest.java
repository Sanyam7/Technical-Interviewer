package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSObject;
import com.techinterviewer.TestAppProperties;
import com.techinterviewer.TestClock;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.Role;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

class JwtServiceTest {

    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0e");

    private final TestClock clock = new TestClock(Instant.parse("2026-10-05T12:00:00.789Z"));
    private final String secret = randomSecret();
    private final AppProperties properties = TestAppProperties.withJwt(secret, false, "tech-interviewer", Duration.ofMinutes(15));
    private final JwtKeyProvider keys = JwtKeyProvider.from(properties.security().jwt());
    private final JwtService service = new JwtService(keys, properties, clock);
    private final JwtDecoder decoder = AccessTokenDecoder.create(keys.signingKey(), "tech-interviewer", clock);

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(String token) throws Exception {
        return JsonMapper.builder().build().readValue(JWSObject.parse(token).getPayload().toString(), Map.class);
    }

    @Test
    void tokenCarriesExactlyTheDocumentedClaims() throws Exception {
        IssuedToken issued = service.issueAccessToken(new AuthenticatedUser(USER, Role.CANDIDATE));

        Map<String, Object> claims = payload(issued.value());

        assertThat(claims).containsOnlyKeys("sub", "role", "iss", "iat", "exp", "jti");
        assertThat(claims).containsEntry("sub", USER.toString()).containsEntry("role", "CANDIDATE").containsEntry("iss", "tech-interviewer");
        assertThat(UUID.fromString((String) claims.get("jti"))).isNotNull();
    }

    @Test
    void issuedAtAndExpiryComeFromTheInjectedClockInWholeSeconds() throws Exception {
        IssuedToken issued = service.issueAccessToken(new AuthenticatedUser(USER, Role.ADMIN));

        Map<String, Object> claims = payload(issued.value());
        long iat = ((Number) claims.get("iat")).longValue();
        long exp = ((Number) claims.get("exp")).longValue();

        assertThat(iat).isEqualTo(Instant.parse("2026-10-05T12:00:00Z").getEpochSecond());
        assertThat(exp - iat).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(15));
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void issuedAtIsTruncatedToTheSecondNotTheMinute() throws Exception {
        clock.set(Instant.parse("2026-10-05T12:00:30.789Z"));

        Map<String, Object> claims = payload(service.issueAccessToken(new AuthenticatedUser(USER, Role.ADMIN)).value());

        assertThat(((Number) claims.get("iat")).longValue()).isEqualTo(Instant.parse("2026-10-05T12:00:30Z").getEpochSecond());
    }

    @Test
    void theHeaderDeclaresHs256() throws Exception {
        String token = service.issueAccessToken(new AuthenticatedUser(USER, Role.CANDIDATE)).value();

        assertThat(JWSObject.parse(token).getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void theLifetimeFollowsTheConfiguration() throws Exception {
        AppProperties shortLived = TestAppProperties.withJwt(secret, false, "tech-interviewer", Duration.ofMinutes(2));
        IssuedToken issued = new JwtService(keys, shortLived, clock).issueAccessToken(new AuthenticatedUser(USER, Role.CANDIDATE));

        Map<String, Object> claims = payload(issued.value());

        assertThat(((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue()).isEqualTo(120);
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void theIssuerFollowsTheConfiguration() throws Exception {
        AppProperties other = TestAppProperties.withJwt(secret, false, "another-issuer", Duration.ofMinutes(15));

        String token = new JwtService(keys, other, clock).issueAccessToken(new AuthenticatedUser(USER, Role.CANDIDATE)).value();

        assertThat(payload(token)).containsEntry("iss", "another-issuer");
    }

    @Test
    void everyTokenGetsAFreshJti() throws Exception {
        Set<Object> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ids.add(payload(service.issueAccessToken(new AuthenticatedUser(USER, Role.CANDIDATE)).value()).get("jti"));
        }

        assertThat(ids).hasSize(50);
    }

    @Test
    void anIssuedTokenIsAcceptedByTheDecoderAndGivesBackTheAccount() {
        for (Role role : Role.values()) {
            Jwt jwt = decoder.decode(service.issueAccessToken(new AuthenticatedUser(USER, role)).value());

            assertThat(AccessTokenClaims.user(jwt)).contains(new AuthenticatedUser(USER, role));
            assertThat(jwt.getIssuedAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
            assertThat(jwt.getExpiresAt()).isEqualTo(Instant.parse("2026-10-05T12:15:00Z"));
        }
    }
}
