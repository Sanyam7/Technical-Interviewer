package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestJson;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Problems written from a servlet filter look exactly like those of the controller advice. */
class ProblemResponseWriterTest {

    private final ProblemResponseWriter writer = new ProblemResponseWriter(TestJson.applicationMapper());

    @Test
    void anApiExceptionIsWrittenWithItsStatusMembersAndHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiException exception = new ApiException(ErrorCode.RATE_LIMITED, "Too many login attempts.")
                .withRetryAfter(Duration.ofSeconds(42))
                .withProperty("limit", 10);

        writer.write(request, response, exception);

        JsonNode body = TestJson.applicationMapper().readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("detail").asString()).isEqualTo("Too many login attempts.");
        assertThat(body.get("type").asString()).isEqualTo("https://techinterviewer.dev/problems/rate-limited");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/auth/login");
        assertThat(body.get("limit").asInt()).isEqualTo(10);
    }

    @Test
    void aPlainCodeAndDetailIsWrittenAsAProblem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(new MockHttpServletRequest("GET", "/api/v1/x"), response, ErrorCode.FORBIDDEN, "No.");

        JsonNode body = TestJson.applicationMapper().readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(body.get("detail").asString()).isEqualTo("No.");
        assertThat(response.getHeaderNames()).doesNotContain("Retry-After");
    }
}
