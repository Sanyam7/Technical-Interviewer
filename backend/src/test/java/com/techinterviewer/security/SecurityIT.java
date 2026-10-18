package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173,https://app.example.com")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIT {

    private static final String PREFLIGHT_METHOD = "Access-Control-Request-Method";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Test
    void protectedPathWithoutCredentialsIsA401ProblemWithTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/interviews").header("X-Request-Id", "trace-for-401-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://techinterviewer.dev/problems/unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/interviews"))
                .andExpect(jsonPath("$.traceId").value("trace-for-401-test"))
                .andExpect(header().string("X-Request-Id", "trace-for-401-test"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void unknownPathsAreDeniedLikeProtectedOnes() throws Exception {
        mockMvc.perform(get("/does/not/exist")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void authenticatedUsersAreStillDeniedWhereNoRuleAllowsThem() throws Exception {
        // Outside /api/v1 nothing is allowed for anybody; inside it, authentication is enough (a missing resource is a 404).
        mockMvc.perform(get("/does/not/exist"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void anAuthenticatedRequestToAnUnknownApiPathIsNotFoundWhereAnAnonymousOneIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-resource").with(TestAuth.bearer(UUID.randomUUID(), Role.CANDIDATE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/no-such-resource")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthDoesNotAcceptWritesWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preflightFromAnAllowedOriginSucceeds() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:5173")
                        .header(PREFLIGHT_METHOD, "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("x-request-id")));
    }

    @Test
    void secondConfiguredOriginIsAlsoAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "https://app.example.com")
                        .header(PREFLIGHT_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"));
    }

    @Test
    void preflightFromAnotherOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://evil.example")
                        .header(PREFLIGHT_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightWithADisallowedMethodIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:5173")
                        .header(PREFLIGHT_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actualRequestExposesTheRequestIdHeaderToBrowsers() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-Id")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void everyApiResponseCarriesAPolicyThatForbidsLoadingAnything() throws Exception {
        for (String path : new String[] {"/api/v1/health", "/api/v1/interviews", "/api/v1/does-not-exist"}) {
            mockMvc.perform(get(path))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                    .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    @Test
    void securityHeadersAreSent() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mockMvc.perform(get("/api/v1/interviews"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void maliciousRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "evil <script>alert(1)</script>"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void swaggerAndApiDocsAreNotServedWithoutTheLocalProfile() throws Exception {
        for (String path : new String[] {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }

    @Test
    void headIsAllowedWhereGetIsPublic() throws Exception {
        mockMvc.perform(head("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(head("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(head("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(head("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void headOnProtectedPathsIsStillDenied() throws Exception {
        mockMvc.perform(head("/api/v1/interviews")).andExpect(status().isUnauthorized());
        mockMvc.perform(head("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthSubPathsAreNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health/x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void forwardedHeadersAreNotTrustedOutsideProd() throws Exception {
        mockMvc.perform(get("/api/v1/interviews")
                        .header("X-Forwarded-Prefix", "/pre")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.instance").value("/api/v1/interviews"));
    }

    @Test
    void preflightWithAForeignRequestHeaderIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:5173")
                        .header(PREFLIGHT_METHOD, "GET")
                        .header("Access-Control-Request-Headers", "x-evil-header"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void noDefaultUserIsCreated() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
