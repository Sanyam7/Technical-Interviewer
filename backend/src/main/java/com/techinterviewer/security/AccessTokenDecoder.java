package com.techinterviewer.security;

import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the decoder that verifies access tokens. It accepts <b>only HS256</b> signed with the configured key: a
 * token with {@code alg: none}, another algorithm (RS256, HS384) or another key is rejected before its claims are
 * read. The issuer must equal the configured one and the claims must pass {@link AccessTokenClaimsValidator}.
 */
public final class AccessTokenDecoder {

    private AccessTokenDecoder() {
    }

    public static JwtDecoder create(SecretKey key, String issuer, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(issuer), new AccessTokenClaimsValidator(clock)));
        return decoder;
    }
}
