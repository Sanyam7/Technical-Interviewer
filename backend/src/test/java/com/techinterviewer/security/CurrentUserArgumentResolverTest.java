package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.user.Role;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserArgumentResolverTest {

    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

    @SuppressWarnings("unused")
    void handler(@CurrentUser AuthenticatedUser annotated, AuthenticatedUser plain, @CurrentUser String wrongType) {
    }

    private MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = CurrentUserArgumentResolverTest.class.getDeclaredMethod("handler", AuthenticatedUser.class, AuthenticatedUser.class, String.class);
        return new MethodParameter(method, index);
    }

    private static Jwt jwt(String subject, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) {
            builder.subject(subject);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        if (subject == null && role == null) {
            builder.claim("x", "y");
        }
        return builder.build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsOnlyTheAnnotatedAuthenticatedUserParameter() throws Exception {
        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        assertThat(resolver.supportsParameter(parameter(1))).as("no annotation: a client could bind it from the request").isFalse();
        assertThat(resolver.supportsParameter(parameter(2))).as("wrong type").isFalse();
    }

    @Test
    void resolvesTheAccountOfTheAccessTokenInTheSecurityContext() throws Exception {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(id.toString(), "ADMIN"), AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        Object user = resolver.resolveArgument(parameter(0), null, null, null);

        assertThat(user).isEqualTo(new AuthenticatedUser(id, Role.ADMIN));
        assertThat(((AuthenticatedUser) user).isAdmin()).isTrue();
        assertThat(new AuthenticatedUser(id, Role.CANDIDATE).isAdmin()).isFalse();
    }

    @Test
    void refusesEveryOtherKindOfAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("someone", "pw", List.of()));
        assertUnauthenticated();

        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertUnauthenticated();

        SecurityContextHolder.clearContext();
        assertUnauthenticated();
    }

    @Test
    void refusesATokenThatDoesNotIdentifyAnAccount() {
        for (Jwt bad : new Jwt[] {jwt("not-a-uuid", "ADMIN"), jwt(UUID.randomUUID().toString(), "ROOT"), jwt(UUID.randomUUID().toString(), null), jwt(null, "ADMIN"), jwt(null, null)}) {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(bad));
            assertUnauthenticated();
        }
    }

    private void assertUnauthenticated() {
        assertThatThrownBy(() -> resolver.resolveArgument(parameter(0), null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
