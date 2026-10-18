package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestJson;
import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * Oversized bodies over real HTTP (the default limits: 16 KiB on the public auth endpoints, 256 KiB elsewhere): a
 * declared length is refused before any parsing, a chunked body is cut off while it is read, and both answer a 413
 * problem document. Ordinary requests are unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RequestSizeLimitIT {

    private static final int AUTH_LIMIT = 16_384;
    private static final int GENERAL_LIMIT = 262_144;

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static String loginBody(int passwordLength) {
        return "{\"email\":\"someone@example.com\",\"password\":\"" + "x".repeat(passwordLength) + "\"}";
    }

    private HttpResponse<String> post(String path, BodyPublisher body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private static BodyPublisher chunked(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
    }

    private String candidateToken() {
        return TestAuth.forContext(context).token(UUID.randomUUID(), Role.CANDIDATE);
    }

    private static void assertTooLarge(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode problem = TestJson.applicationMapper().readTree(response.body());
        assertThat(problem.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(problem.get("traceId").asString()).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void anOversizedLoginBodyWithADeclaredLengthIsRefused() throws Exception {
        assertTooLarge(post("/api/v1/auth/login", BodyPublishers.ofString(loginBody(AUTH_LIMIT)), null));
    }

    @Test
    void anOversizedLoginBodySentChunkedIsCutOffWhileItIsRead() throws Exception {
        assertTooLarge(post("/api/v1/auth/login", chunked(loginBody(AUTH_LIMIT)), null));
    }

    @Test
    void aLoginBodyJustUnderTheLimitReachesTheEndpoint() throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login",
                BodyPublishers.ofString(loginBody(AUTH_LIMIT - 100)), null);

        assertThat(response.statusCode()).as("too long a password is a validation error, not a size error").isEqualTo(400);
        assertThat(TestJson.applicationMapper().readTree(response.body()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void anOversizedBodyOnAnAuthenticatedEndpointIsRefusedToo() throws Exception {
        String body = "{\"headline\":\"" + "x".repeat(GENERAL_LIMIT) + "\",\"skills\":[]}";

        assertTooLarge(put("/api/v1/profile", BodyPublishers.ofString(body), candidateToken()));
        assertTooLarge(put("/api/v1/profile", chunked(body), candidateToken()));
    }

    @Test
    void aBodyBetweenTheAuthLimitAndTheGeneralLimitIsFineOutsideTheAuthEndpoints() throws Exception {
        String body = "{\"headline\":\"" + "x".repeat(AUTH_LIMIT * 2) + "\",\"skills\":[]}";

        HttpResponse<String> response = put("/api/v1/profile", BodyPublishers.ofString(body), candidateToken());

        assertThat(response.statusCode()).as("rejected by validation (headline too long), not by the size limit").isEqualTo(400);
        assertThat(TestJson.applicationMapper().readTree(response.body()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    private HttpResponse<String> put(String path, BodyPublisher body, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .PUT(body)
                .build(), BodyHandlers.ofString());
    }
}
