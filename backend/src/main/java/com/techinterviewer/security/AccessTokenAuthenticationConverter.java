package com.techinterviewer.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified access token into the authentication of the request: the principal name is the user id
 * ({@code sub}) and the single authority is {@code ROLE_CANDIDATE} or {@code ROLE_ADMIN}, taken from the
 * {@code role} claim. The claims were already validated, so a token that reaches here has both.
 */
final class AccessTokenAuthenticationConverter implements Converter<Jwt, AbstractOAuth2TokenAuthenticationToken<Jwt>> {

    @Override
    public AbstractOAuth2TokenAuthenticationToken<Jwt> convert(Jwt jwt) {
        AuthenticatedUser user = AccessTokenClaims.user(jwt)
                .orElseThrow(() -> new IllegalStateException("A validated access token must identify an account"));
        return new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())), user.id().toString());
    }
}
