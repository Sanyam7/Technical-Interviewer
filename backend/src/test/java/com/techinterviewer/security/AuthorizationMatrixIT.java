package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.techinterviewer.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Deny by default, checked on the application's own routing table (SECURITY.md, AUTHZ-1). Every route the application maps under
 * {@code /api/v1} is called without a token, with garbage as a token and with a forged token ({@code alg: none}, claiming
 * to be an administrator): unless the route is on the public list, each of these must be refused with 401 before any handler runs.
 * The public list itself is pinned here, so a new public route is a decision that has to change this test.
 */
@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthorizationMatrixIT {

    /** The operations that need no token. A route is added here on purpose or not at all. */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/health",
            "GET /api/v1/catalog/options", "GET /api/v1/catalog/skills", "GET /api/v1/catalog/skills/{slug}/topics",
            "POST /api/v1/auth/register", "POST /api/v1/auth/login", "POST /api/v1/auth/refresh", "POST /api/v1/auth/logout");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private record Operation(String method, String pattern) {
        String path() {
            return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private List<Operation> operations() {
        List<Operation> operations = new java.util.ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/v1")) {
                    continue;
                }
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    operations.add(new Operation(method.name(), pattern));
                }
            }
        }
        return operations;
    }

    private static String forgedToken() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"ADMIN\",\"iss\":\"tech-interviewer\","
                + "\"exp\":4102444800}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private int statusOf(Operation operation, String authorization) throws Exception {
        var builder = request(HttpMethod.valueOf(operation.method()), operation.path());
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        return result.getResponse().getStatus();
    }

    @Test
    void thePublicRoutesAreExactlyTheOnesListedHere() {
        Set<String> found = new TreeSet<>();
        for (Operation operation : operations()) {
            if (PublicEndpoints.isPublic(operation.method(), operation.pattern())) {
                found.add(operation.toString());
            }
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(PUBLIC);
    }

    @Test
    void everyOtherRouteRefusesNoTokenAGarbageTokenAndAForgedAdministratorToken() throws Exception {
        List<Operation> protectedOperations = operations().stream().filter(operation -> !PUBLIC.contains(operation.toString())).toList();
        assertThat(protectedOperations).as("the routing table was found").hasSizeGreaterThan(25);

        Map<String, String> attempts = Map.of("no token", "", "garbage", "Bearer not.a.token", "forged alg none", "Bearer " + forgedToken(),
                "wrong scheme", "Basic YWRtaW46YWRtaW4=");
        for (Operation operation : protectedOperations) {
            for (var attempt : attempts.entrySet()) {
                assertThat(statusOf(operation, attempt.getValue().isEmpty() ? null : attempt.getValue()))
                        .as("%s with %s", operation, attempt.getKey()).isEqualTo(401);
            }
        }
    }

    @Test
    void noRouteOutsideTheApiPrefixIsReachableWithoutAToken() throws Exception {
        for (String path : new String[] {"/", "/admin", "/api", "/api/v2/health", "/h2-console", "/actuator", "/actuator/env",
            "/actuator/beans", "/actuator/heapdump", "/actuator/prometheus", "/actuator/loggers", "/error"}) {
            int status = mockMvc.perform(request(HttpMethod.GET, path)).andReturn().getResponse().getStatus();
            assertThat(status).as("GET %s", path).isIn(401, 403, 404);
        }
    }
}
