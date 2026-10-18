package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.common.error.RequestBodyTooLargeException;
import com.techinterviewer.config.AppProperties;
import jakarta.servlet.FilterChain;
import java.io.CharArrayWriter;
import java.io.Reader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** The size filter in isolation, with small limits (1000 bytes in general, 200 on the auth endpoints). */
class RequestSizeLimitFilterTest {

    private static final int LIMIT = 1_000;
    private static final int AUTH_LIMIT = 200;

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(
            new AppProperties(
                    new AppProperties.Cors(List.of("http://localhost:5173")),
                    new AppProperties.Security(
                            new AppProperties.Jwt(null, false, "tech-interviewer", Duration.ofMinutes(15)),
                            new AppProperties.RefreshCookie("refresh_token", true, Duration.ofDays(14), "/api/v1/auth")),
                    new AppProperties.RateLimit(10, 20, 10, 60, 10, 10, 10, java.time.Duration.ofMinutes(15)),
                    new AppProperties.Interview(Duration.ofSeconds(30), 2, 4),
                    new AppProperties.Limits(LIMIT, AUTH_LIMIT, 2_162_688)),
            new ProblemResponseWriter(TestJson.applicationMapper()));

    private static MockHttpServletRequest declared(String path, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(new byte[bytes]);
        return request;
    }

    /** A request without a declared length, as a chunked upload looks to the servlet. */
    private static MockHttpServletRequest chunked(String path, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void aBodyThatDeclaresMoreThanTheLimitIsRefusedBeforeTheEndpointRuns() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(declared("/api/v1/profile", LIMIT + 1), response, (req, res) -> reached.set(true));

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode problem = TestJson.applicationMapper().readTree(response.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(problem.get("status").asInt()).isEqualTo(413);
        assertThat(problem.get("instance").asString()).isEqualTo("/api/v1/profile");
        assertThat(problem.get("detail").asString()).contains(String.valueOf(LIMIT));
    }

    @Test
    void aBodyExactlyAtTheLimitPasses() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(declared("/api/v1/profile", LIMIT), response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void thePublicAuthEndpointsHaveTheSmallerLimit() throws Exception {
        MockHttpServletResponse tooBig = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(declared("/api/v1/auth/login", AUTH_LIMIT + 1), tooBig, (req, res) -> reached.set(true));
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(declared("/api/v1/auth/login", AUTH_LIMIT), allowed, (req, res) -> reached.set(true));

        assertThat(tooBig.getStatus()).isEqualTo(413);
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(reached).isTrue();
        MockHttpServletResponse otherPath = new MockHttpServletResponse();
        AtomicBoolean otherReached = new AtomicBoolean();
        filter.doFilter(declared("/api/v1/profile", AUTH_LIMIT + 1), otherPath, (req, res) -> otherReached.set(true));
        assertThat(otherReached).as("the larger general limit applies outside /auth/").isTrue();
    }

    @Test
    void aChunkedBodyIsCutOffOnceItGrowsPastTheLimit() {
        MockHttpServletRequest request = chunked("/api/v1/profile", LIMIT + 1);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (FilterChain) (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodyTooLargeException.class)
                .extracting(error -> ((RequestBodyTooLargeException) error).limitBytes())
                .isEqualTo((long) LIMIT);
    }

    @Test
    void aChunkedBodyAtTheLimitIsReadInFull() throws Exception {
        MockHttpServletRequest request = chunked("/api/v1/profile", LIMIT);
        int[] seen = new int[1];

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen[0] = req.getInputStream().readAllBytes().length);

        assertThat(seen[0]).isEqualTo(LIMIT);
    }

    @Test
    void theReaderOfAChunkedBodyIsCountedToo() {
        MockHttpServletRequest request = chunked("/api/v1/profile", LIMIT + 1);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> drain(req.getReader())))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void theAuthLimitAlsoAppliesToAChunkedBody() {
        MockHttpServletRequest request = chunked("/api/v1/auth/register", AUTH_LIMIT + 1);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (FilterChain) (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void aRequestWithoutABodyPassesUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static void drain(Reader reader) throws java.io.IOException {
        CharArrayWriter sink = new CharArrayWriter();
        reader.transferTo(sink);
    }
}
