package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.InterviewApiTestBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Nothing a client sends makes the server fail: every write endpoint is sent a matrix of malformed bodies (wrong JSON types,
 * null, arrays, extreme numbers, deep nesting, invalid UTF-8, NUL characters, a wrong content type) and every read endpoint
 * malformed ids and paging, and each answer must be a 4xx problem document, never a 5xx. The point is the sweep: individual
 * endpoints test their own validation; this catches the input nobody thought of for one of them.
 */
class MalformedRequestsIT extends InterviewApiTestBase {

    /** The sweep sends hundreds of requests from one address: the rate limits would answer most of them before validation does. */
    @org.springframework.test.context.DynamicPropertySource
    static void noThrottling(org.springframework.test.context.DynamicPropertyRegistry registry) {
        for (String limit : List.of("auth-per-minute", "answers-per-minute", "writes-per-minute", "code-runs-per-minute",
                "login-failures-per-account", "interviews-per-day")) {
            registry.add("app.rate-limit." + limit, () -> "1000");
        }
    }

    /** Bodies no endpoint accepts. */
    private static final List<String> BODIES = List.of(
            "",
            "null",
            "[]",
            "\"text\"",
            "42",
            "{",
            "{}",
            "{\"content\":null,\"questionId\":null,\"reason\":null,\"email\":null,\"configuration\":null}",
            "{\"content\":[],\"questionId\":{},\"reason\":7,\"email\":true,\"configuration\":\"x\"}",
            "{\"content\":\"a\\u0000b\",\"questionId\":\"not-a-uuid\",\"reason\":\"NOT_A_REASON\"}",
            "{\"timeTakenSeconds\":1e400,\"durationMinutes\":-99999999999999999999,\"yearsExperience\":1.5e308}",
            "{\"configuration\":{\"skills\":[" + "\"java\",".repeat(500) + "\"java\"],\"durationMinutes\":30}}",
            "{\"a\":1,\"a\":2}",
            "[".repeat(2000) + "]".repeat(2000),
            "{\"x\":" + "{\"x\":".repeat(2000) + "1" + "}".repeat(2001));

    private record Endpoint(HttpMethod method, String path, boolean authenticated) {
    }

    private List<Endpoint> writeEndpoints(UUID interview, UUID question) {
        String i = "/interviews/" + interview;
        return List.of(
                new Endpoint(HttpMethod.POST, "/auth/register", false),
                new Endpoint(HttpMethod.POST, "/auth/login", false),
                new Endpoint(HttpMethod.PUT, "/profile", true),
                new Endpoint(HttpMethod.POST, "/interviews", true),
                new Endpoint(HttpMethod.POST, "/interview-configurations", true),
                new Endpoint(HttpMethod.POST, i + "/answers", true),
                new Endpoint(HttpMethod.POST, i + "/questions/" + question + "/skip", true),
                new Endpoint(HttpMethod.POST, i + "/questions/" + question + "/runs", true),
                new Endpoint(HttpMethod.POST, i + "/finish", true));
    }

    private MockHttpServletResponse send(UUID user, Endpoint endpoint, MockHttpServletRequestBuilder builder) throws Exception {
        if (endpoint.authenticated()) {
            builder.with(as(user));
        }
        return mockMvc.perform(builder).andReturn().getResponse();
    }

    private static String describe(Endpoint endpoint, String what, MockHttpServletResponse response) throws Exception {
        return endpoint.method() + " " + endpoint.path() + " with " + (what.length() > 60 ? what.substring(0, 60) + "..." : what)
                + " -> " + response.getStatus() + " " + response.getContentAsString().substring(0, Math.min(200, response.getContentAsString().length()));
    }

    @Test
    void noMalformedBodyMakesAnyWriteEndpointFail() throws Exception {
        UUID user = newUser();
        UUID interview = startedInterview(user);
        UUID question = UUID.fromString(body(getAs(user, "/interviews/" + interview)).get("currentQuestion").get("id").asString());
        List<String> serverErrors = new ArrayList<>();
        java.util.Map<Integer, Integer> statuses = new java.util.TreeMap<>();
        List<String> accepted = new ArrayList<>();

        for (Endpoint endpoint : writeEndpoints(interview, question)) {
            for (String body : BODIES) {
                MockHttpServletResponse response = send(user, endpoint, request(endpoint.method(), API + endpoint.path())
                        .contentType(MediaType.APPLICATION_JSON).content(body));
                statuses.merge(response.getStatus(), 1, Integer::sum);
                if (response.getStatus() < 400) {
                    accepted.add(describe(endpoint, body, response));
                }
                if (response.getStatus() >= 500) {
                    serverErrors.add(describe(endpoint, body, response));
                } else if (response.getStatus() >= 400) {
                    assertThat(response.getContentType()).as(describe(endpoint, body, response)).contains("problem+json");
                }
            }
            byte[] invalidUtf8 = {'{', '"', 'c', 'o', 'n', 't', 'e', 'n', 't', '"', ':', '"', (byte) 0xC3, (byte) 0x28, (byte) 0xFF, '"', '}'};
            for (MockHttpServletRequestBuilder odd : List.of(
                    request(endpoint.method(), API + endpoint.path()).contentType(MediaType.APPLICATION_JSON).content(invalidUtf8),
                    request(endpoint.method(), API + endpoint.path()).contentType(MediaType.TEXT_PLAIN).content("content=hello"),
                    request(endpoint.method(), API + endpoint.path()).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("content=hello"),
                    request(endpoint.method(), API + endpoint.path()).content("{}".getBytes(StandardCharsets.UTF_8)))) {
                MockHttpServletResponse response = send(user, endpoint, odd);
                if (response.getStatus() >= 500) {
                    serverErrors.add(describe(endpoint, "an odd encoding or content type", response));
                }
            }
        }

        assertThat(serverErrors).as("requests a client can send that made the server fail").isEmpty();
        // The sweep reached the endpoints' own validation: it was not simply throttled.
        int total = statuses.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(statuses.getOrDefault(429, 0)).as("throttled requests among " + statuses).isLessThan(total / 10);
        assertThat(statuses.getOrDefault(400, 0)).as("rejected as invalid among " + statuses).isGreaterThan(total / 2);
        // The only bodies of the matrix an endpoint may accept: none at all, null or {} for a skip, whose body is optional
        // (API_DESIGN.md section 6.4), and {} for the profile, whose fields are all optional.
        assertThat(accepted).allMatch(line -> line.matches("POST /interviews/\\S+/skip with (|null|\\{\\}) -> 200 .*")
                || line.startsWith("PUT /profile with {} -> 200"), "accepted: " + accepted);
    }

    @Test
    void noMalformedIdOrPagingMakesAnyReadEndpointFail() throws Exception {
        UUID user = newUser();
        UUID interview = startedInterview(user);
        List<String> paths = new ArrayList<>();
        for (String id : List.of("not-a-uuid", "00000000-0000-0000-0000-000000000000", "%00", "..%2F..%2Fetc", "1e400", interview + "x")) {
            for (String suffix : List.of("", "/state", "/questions", "/questions/current", "/questions/" + id, "/transcript", "/report")) {
                paths.add("/interviews/" + id + suffix);
            }
            paths.add("/interview-configurations/" + id);
            paths.add("/catalog/skills/" + id + "/topics");
        }
        for (String paging : List.of("page=-1", "page=999999999999", "size=0", "size=100000", "page=abc", "state=NOT_A_STATE",
                "state=COMPLETED&state=INIT", "sort=password", "page=1e3")) {
            paths.add("/interviews?" + paging);
            paths.add("/interview-configurations?" + paging);
        }
        List<String> serverErrors = new ArrayList<>();
        for (String path : paths) {
            MockHttpServletResponse response = mockMvc.perform(request(HttpMethod.GET, API + path).with(as(user))).andReturn().getResponse();
            if (response.getStatus() >= 500) {
                serverErrors.add("GET " + path + " -> " + response.getStatus() + " " + response.getContentAsString());
            }
        }
        assertThat(serverErrors).as("requests a client can send that made the server fail").isEmpty();
        // The interview is untouched by all of it.
        getAs(user, "/interviews/" + interview).andExpect(status().isOk());
    }
}
