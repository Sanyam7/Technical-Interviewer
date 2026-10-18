package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestJson;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.JsonNode;

class RateLimitFilterTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ANSWERS = "/api/v1/interviews/abc/answers";

    /** The end of the chain: answers 200. */
    private static final class OkServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            response.setStatus(200);
        }
    }

    /** What one request got: the response, and whether it reached the end of the chain. */
    private record Outcome(MockHttpServletResponse response, boolean passed) {
    }

    private final TestClock clock = new TestClock();
    private final ProblemResponseWriter problems = new ProblemResponseWriter(TestJson.applicationMapper());
    private final PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RateLimiter limiter(int permits) {
        return new RateLimiter(permits, Duration.ofMinutes(1), 100, clock);
    }

    private RateLimitFilter perAddress(int permits) {
        return RateLimitFilter.perClientAddress("test", paths.matcher(HttpMethod.POST, LOGIN), limiter(permits), problems);
    }

    private RateLimitFilter perUser(int permits) {
        RequestMatcher answers = paths.matcher(HttpMethod.POST, "/api/v1/interviews/*/answers");
        return RateLimitFilter.perUser("answers", answers, limiter(permits), problems);
    }

    private static Outcome send(RateLimitFilter filter, String method, String path, String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain.getRequest() != null);
    }

    private static Outcome post(RateLimitFilter filter, String path, String remoteAddress) throws Exception {
        return send(filter, "POST", path, remoteAddress);
    }

    private static void authenticate(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(userId.toString()).claim("role", "CANDIDATE").build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_CANDIDATE"), userId.toString()));
    }

    @Test
    void requestsWithinTheAllowancePassAndTheNextOneIsAnswered429AsAProblem() throws Exception {
        RateLimitFilter filter = perAddress(2);
        assertThat(post(filter, LOGIN, "198.51.100.7").passed()).isTrue();
        assertThat(post(filter, LOGIN, "198.51.100.7").passed()).isTrue();

        Outcome limited = post(filter, LOGIN, "198.51.100.7");

        assertThat(limited.passed()).isFalse();
        MockHttpServletResponse response = limited.response();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        JsonNode body = TestJson.applicationMapper().readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("instance").asString()).isEqualTo(LOGIN);
        assertThat(body.get("detail").asString()).isEqualTo(RateLimitFilter.LIMITED_DETAIL);
    }

    @Test
    void retryAfterIsInWholeSecondsRoundedUp() throws Exception {
        RateLimitFilter filter = perAddress(1);
        post(filter, LOGIN, "198.51.100.7");
        clock.advance(Duration.ofMillis(20_300));

        assertThat(post(filter, LOGIN, "198.51.100.7").response().getHeader("Retry-After")).as("39.7 s remain").isEqualTo("40");
    }

    @Test
    void theAllowanceIsPerClientAddress() throws Exception {
        RateLimitFilter filter = perAddress(1);

        assertThat(post(filter, LOGIN, "198.51.100.7").passed()).isTrue();
        assertThat(post(filter, LOGIN, "198.51.100.7").passed()).isFalse();
        assertThat(post(filter, LOGIN, "198.51.100.8").passed()).as("another address").isTrue();
    }

    @Test
    void requestsThatDoNotMatchAreNeitherCountedNorLimited() throws Exception {
        RateLimitFilter filter = perAddress(1);

        for (int i = 0; i < 5; i++) {
            assertThat(post(filter, "/api/v1/auth/logout", "198.51.100.7").passed()).isTrue();
            assertThat(send(filter, "GET", LOGIN, "198.51.100.7").passed()).as("GET is not limited").isTrue();
        }
        assertThat(post(filter, LOGIN, "198.51.100.7").passed()).as("the matching request still has its full allowance").isTrue();
    }

    @Test
    void thePerUserFilterCountsPerAuthenticatedUserAndLeavesAnonymousRequestsToTheAuthorizationRules() throws Exception {
        RateLimitFilter filter = perUser(1);

        for (int i = 0; i < 3; i++) {
            assertThat(post(filter, ANSWERS, "198.51.100.7").passed()).as("anonymous request %d", i + 1).isTrue();
        }
        authenticate(UUID.randomUUID());
        assertThat(post(filter, ANSWERS, "198.51.100.7").passed()).isTrue();
        assertThat(post(filter, ANSWERS, "198.51.100.7").passed()).as("same user, second request").isFalse();
        assertThat(post(filter, "/api/v1/interviews/other/answers", "198.51.100.9").passed())
                .as("same user, another interview and address").isFalse();
        authenticate(UUID.randomUUID());
        assertThat(post(filter, ANSWERS, "198.51.100.7").passed()).as("another user from the same address").isTrue();
    }

    @Test
    void twoFiltersOfTheSameClassInOneChainEachApplyTheirOwnLimit() throws Exception {
        RateLimitFilter strict = RateLimitFilter.perClientAddress("strict", paths.matcher(HttpMethod.POST, LOGIN), limiter(1), problems);
        RateLimitFilter lenient = RateLimitFilter.perClientAddress("lenient", paths.matcher(HttpMethod.POST, LOGIN), limiter(100), problems);

        for (int attempt = 1; attempt <= 2; attempt++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN);
            request.setRemoteAddr("198.51.100.7");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new MockFilterChain(new OkServlet(), strict, lenient).doFilter(request, response);

            assertThat(response.getStatus()).as("attempt %d", attempt).isEqualTo(attempt == 1 ? 200 : 429);
        }
    }

    @Test
    void aRequestThatPassesTheSameFilterTwiceIsCountedOnce() throws Exception {
        RateLimitFilter filter = perAddress(1);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN);
        request.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new MockFilterChain(new OkServlet(), filter, filter).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
