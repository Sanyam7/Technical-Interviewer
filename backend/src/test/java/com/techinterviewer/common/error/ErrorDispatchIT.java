package com.techinterviewer.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP requests against a running server. MockMvc never performs the servlet container's error
 * dispatch, so only this test sees what Spring Security's firewall rejections, unknown actuator paths
 * and other {@code sendError} responses look like to a client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ErrorDispatchIT {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ApplicationContext context;

    static Stream<Arguments> rejectedRequests() {
        String[] accepts = {null, "text/html", "garbage", "application/xml"};
        Stream.Builder<Arguments> arguments = Stream.builder();
        for (String accept : accepts) {
            arguments.add(Arguments.of("//api/v1/health", accept, 400, "BAD_REQUEST"));
            arguments.add(Arguments.of("/api/v1/health;x=1", accept, 400, "BAD_REQUEST"));
            arguments.add(Arguments.of("/api/v1/./health", accept, 400, "BAD_REQUEST"));
            arguments.add(Arguments.of("/api/v1/a%25b", accept, 400, "BAD_REQUEST"));
            arguments.add(Arguments.of("/actuator/health/nosuchgroup", accept, 401, "UNAUTHENTICATED"));
            arguments.add(Arguments.of("/v3/api-docs", accept, 404, "NOT_FOUND"));
        }
        return arguments.build();
    }

    static Stream<Arguments> unacceptableRequests() {
        return Stream.of("text/html", "garbage", "application/xml").flatMap(accept -> Stream.of(
                Arguments.of("/api/v1/health", accept, 406, "NOT_ACCEPTABLE"),
                Arguments.of("/actuator/health", accept, 406, "NOT_ACCEPTABLE")));
    }

    @ParameterizedTest(name = "{0} with Accept {1} is a {2} problem")
    @MethodSource({"rejectedRequests", "unacceptableRequests"})
    void errorsAreProblemJsonWhateverTheAcceptHeader(String path, String accept, int status, String code)
            throws Exception {
        HttpResponse<String> response = get(path, accept);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("type").asString()).startsWith("https://techinterviewer.dev/problems/");
        assertThat(body.get("title").asString()).isNotBlank();
        assertThat(body.get("detail").asString()).isNotBlank();
        assertThat(body.get("traceId").asString()).isEqualTo(requestId);
        assertThat(body.get("instance").asString()).isEqualTo(path);
    }

    @Test
    void containerErrorsNeverRevealInternals() throws Exception {
        HttpResponse<String> response = get("//api/v1/health", null);

        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "traceId");
        assertThat(body.get("detail").asString()).isEqualTo(ProblemErrorController.CLIENT_ERROR_DETAIL);
        assertThat(response.body()).doesNotContain("Exception", "StrictHttpFirewall", "org.springframework");
    }

    @Test
    void aClientSuppliedRequestIdIsEchoedInContainerErrors() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("//api/v1/health"))
                .header("X-Request-Id", "client-supplied-id-42")
                .build();

        JsonNode body = jsonMapper.readTree(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body());

        assertThat(body.get("traceId").asString()).isEqualTo("client-supplied-id-42");
    }

    @Test
    void theProblemControllerReplacesBootsBasicErrorController() {
        assertThat(context.getBeansOfType(ErrorController.class).values())
                .singleElement()
                .isInstanceOf(ProblemErrorController.class);
    }

    @Test
    void theErrorPathIsNotReachableDirectly() throws Exception {
        HttpResponse<String> response = get("/error", null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String rawPath, String accept) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(rawPath));
        if (accept != null) {
            request.header("Accept", accept);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String rawPath) {
        return URI.create("http://localhost:" + port + rawPath);
    }
}
