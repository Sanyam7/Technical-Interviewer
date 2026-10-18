package com.techinterviewer.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.techinterviewer.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issues access tokens: HS256 JWTs with the claims {@code sub} (user id), {@code role}, {@code iss}, {@code iat},
 * {@code exp} and {@code jti}, signed with Nimbus. Time comes from the injected {@link Clock}.
 */
public class JwtService {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(JwtKeyProvider keys, AppProperties properties, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(keys.signingKey()));
        this.issuer = properties.security().jwt().issuer();
        this.accessTokenTtl = properties.security().jwt().accessTokenTtl();
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(AuthenticatedUser user) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.id().toString())
                .claim(AccessTokenClaims.ROLE, user.role().name())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(accessTokenTtl))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, accessTokenTtl);
    }
}
