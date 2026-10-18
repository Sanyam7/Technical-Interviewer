package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestClockConfiguration;
import com.techinterviewer.TestJson;
import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The request limits in the real filter chain (decision D6): per client address on the public auth endpoints, per
 * authenticated user on answer submission through the real answers endpoint (which answers 404 to an interview that
 * does not exist: the limiter counts the request before the endpoint decides anything), with a clock that the test moves.
 */
@SpringBootTest(properties = {
    "app.cors.allowed-origins=http://localhost:5173",
    "app.rate-limit.auth-per-minute=3",
    "app.rate-limit.answers-per-minute=2",
    "app.rate-limit.writes-per-minute=2"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class RateLimitIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startAnEmptyWindow() {
        jdbc.execute("truncate table users cascade");
        clock.advance(Duration.ofMinutes(5));
    }

    private MvcResult loginFrom(String address) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"some-password-1\"}"))
                .andReturn();
    }

    /** An answer to a question of an interview that does not exist: the real endpoint answers 404, or 429 once the allowance is used. */
    private MvcResult answerAs(UUID user, String interview) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/interviews/" + UUID.nameUUIDFromBytes(interview.getBytes()) + "/answers")
                .with(TestAuth.forContext(context).asUser(user, Role.CANDIDATE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":\"" + UUID.nameUUIDFromBytes("question".getBytes()) + "\",\"content\":\"my answer\"}");
        return mockMvc.perform(request).andReturn();
    }

    private static final int ALLOWED = 404;

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    @Test
    void theFourthAuthRequestOfAnAddressWithinTheMinuteIs429WithRetryAfterAndAProblemBody() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(status(loginFrom("203.0.113.1"))).isEqualTo(401);
        }

        MvcResult limited = loginFrom("203.0.113.1");

        assertThat(status(limited)).isEqualTo(429);
        assertThat(limited.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
        JsonNode body = TestJson.applicationMapper().readTree(limited.getResponse().getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/auth/login");
    }

    @Test
    void theAuthAllowanceIsPerAddressAndAForwardedForHeaderDoesNotChangeTheAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            loginFrom("203.0.113.1");
        }

        assertThat(status(loginFrom("203.0.113.1"))).isEqualTo(429);
        assertThat(status(loginFrom("203.0.113.2"))).isEqualTo(401);
        MvcResult spoofed = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.77")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.1");
                            return request;
                        })
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"some-password-1\"}"))
                .andReturn();
        assertThat(status(spoofed)).isEqualTo(429);
    }

    @Test
    void registerLoginAndRefreshShareTheAllowanceOfAnAddressAndLogoutIsNotLimited() throws Exception {
        MockHttpServletRequestBuilder register = post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bad\",\"password\":\"x\",\"displayName\":\"x\"}");
        assertThat(status(mockMvc.perform(register).andReturn())).isEqualTo(400);
        assertThat(status(loginFrom("127.0.0.1"))).isEqualTo(401);
        assertThat(status(mockMvc.perform(post("/api/v1/auth/refresh")).andReturn())).isEqualTo(403);

        assertThat(status(mockMvc.perform(register).andReturn())).isEqualTo(429);
        assertThat(status(mockMvc.perform(post("/api/v1/auth/refresh")).andReturn())).isEqualTo(429);
        assertThat(status(mockMvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest")).andReturn()))
                .isEqualTo(204);
    }

    @Test
    void theAuthAllowanceComesBackWithTheNextWindowAndRetryAfterCountsDown() throws Exception {
        for (int i = 0; i < 3; i++) {
            loginFrom("203.0.113.1");
        }
        clock.advance(Duration.ofSeconds(20));
        MvcResult stillLimited = loginFrom("203.0.113.1");
        assertThat(status(stillLimited)).isEqualTo(429);
        assertThat(stillLimited.getResponse().getHeader("Retry-After")).isEqualTo("40");

        clock.advance(Duration.ofSeconds(39));
        assertThat(status(loginFrom("203.0.113.1"))).isEqualTo(429);
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 3; i++) {
            assertThat(status(loginFrom("203.0.113.1"))).isEqualTo(401);
        }
        assertThat(status(loginFrom("203.0.113.1"))).isEqualTo(429);
    }

    @Test
    void answerSubmissionIsLimitedPerUserNotPerAddress() throws Exception {
        UUID ada = UUID.randomUUID();
        UUID grace = UUID.randomUUID();
        assertThat(status(answerAs(ada, "a"))).isEqualTo(ALLOWED);
        assertThat(status(answerAs(ada, "b"))).isEqualTo(ALLOWED);

        MvcResult limited = answerAs(ada, "c");

        assertThat(status(limited)).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getResponse().getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(status(answerAs(grace, "a"))).as("same address, another user").isEqualTo(ALLOWED);
        assertThat(status(answerAs(grace, "a"))).isEqualTo(ALLOWED);
        assertThat(status(answerAs(grace, "a"))).isEqualTo(429);
    }

    @Test
    void theAnswerAllowanceIsBackAfterTheWindowAndOtherEndpointsAreNotCounted() throws Exception {
        UUID ada = UUID.randomUUID();
        answerAs(ada, "a");
        answerAs(ada, "a");
        assertThat(status(answerAs(ada, "a"))).isEqualTo(429);
        MvcResult skip = mockMvc.perform(post("/api/v1/interviews/" + UUID.randomUUID() + "/questions/" + UUID.randomUUID() + "/skip")
                .with(TestAuth.forContext(context).asUser(ada, Role.CANDIDATE))).andReturn();
        assertThat(status(skip)).isEqualTo(ALLOWED);

        clock.advance(Duration.ofSeconds(59));
        assertThat(status(answerAs(ada, "a"))).isEqualTo(429);
        clock.advance(Duration.ofSeconds(1));
        assertThat(status(answerAs(ada, "a"))).isEqualTo(ALLOWED);
    }

    @Test
    void unauthenticatedAnswerRequestsAreRejectedWithoutConsumingAnyone() throws Exception {
        UUID ada = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertThat(status(mockMvc.perform(post("/api/v1/interviews/" + UUID.randomUUID() + "/answers")).andReturn())).isEqualTo(401);
            assertThat(status(mockMvc.perform(post("/api/v1/interviews/" + UUID.randomUUID() + "/answers")
                    .header("Authorization", "Bearer forged.token.value")).andReturn())).isEqualTo(401);
        }

        assertThat(status(answerAs(ada, "a"))).isEqualTo(ALLOWED);
    }

    @Test
    void theAuthAndAnswerLimitsAreIndependent() throws Exception {
        UUID ada = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            loginFrom("127.0.0.1");
        }
        assertThat(status(loginFrom("127.0.0.1"))).isEqualTo(429);

        assertThat(status(answerAs(ada, "a"))).isEqualTo(ALLOWED);
    }

    /** A write that the endpoint refuses with 400 (empty body): the limiter counts it before the endpoint decides. */
    private MvcResult writeAs(UUID user) throws Exception {
        return mockMvc.perform(post("/api/v1/interview-configurations")
                        .with(TestAuth.forContext(context).asUser(user, Role.CANDIDATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    @Test
    void otherStateChangingRequestsAreLimitedPerUserWithTheirOwnAllowance() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThat(status(writeAs(user))).isEqualTo(400);
        assertThat(status(writeAs(user))).isEqualTo(400);
        MvcResult limited = writeAs(user);

        assertThat(status(limited)).isEqualTo(429);
        assertThat(limited.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(status(writeAs(other))).as("another user has their own allowance").isEqualTo(400);
        assertThat(status(mockMvc.perform(get("/api/v1/interview-configurations")
                .with(TestAuth.forContext(context).asUser(user, Role.CANDIDATE))).andReturn()))
                .as("reads are never limited by the writes allowance").isEqualTo(200);
    }

    @Test
    void putAndDeleteRequestsShareTheWritesAllowance() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThat(status(putProfileAs(user))).isNotEqualTo(429);
        assertThat(status(deleteConfigurationAs(user))).isNotEqualTo(429);
        assertThat(status(putProfileAs(user))).as("PUT and DELETE used the allowance of 2").isEqualTo(429);
        assertThat(status(deleteConfigurationAs(user))).isEqualTo(429);
        assertThat(status(writeAs(user))).as("POST shares the same allowance").isEqualTo(429);
        assertThat(status(deleteConfigurationAs(other))).as("another user is unaffected").isNotEqualTo(429);
    }

    private MvcResult putProfileAs(UUID user) throws Exception {
        return mockMvc.perform(put("/api/v1/profile").contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(TestAuth.forContext(context).asUser(user, Role.CANDIDATE))).andReturn();
    }

    private MvcResult deleteConfigurationAs(UUID user) throws Exception {
        return mockMvc.perform(delete("/api/v1/interview-configurations/" + UUID.randomUUID())
                .with(TestAuth.forContext(context).asUser(user, Role.CANDIDATE))).andReturn();
    }

    @Test
    void answersLoginAndRegistrationDoNotUseTheWritesAllowance() throws Exception {
        UUID user = UUID.randomUUID();

        assertThat(status(answerAs(user, "a"))).isEqualTo(ALLOWED);
        assertThat(status(answerAs(user, "b"))).isEqualTo(ALLOWED);
        assertThat(status(loginFrom("203.0.113.40"))).isEqualTo(401);
        assertThat(status(writeAs(user))).as("the two answers did not consume the writes allowance").isEqualTo(400);
        assertThat(status(writeAs(user))).isEqualTo(400);
        assertThat(status(writeAs(user))).isEqualTo(429);
        assertThat(status(answerAs(user, "c"))).as("answers have their own limit: 2 per minute").isEqualTo(429);
    }

    @Test
    void theWritesAllowanceComesBackAfterTheWindow() throws Exception {
        UUID user = UUID.randomUUID();
        writeAs(user);
        writeAs(user);
        assertThat(status(writeAs(user))).isEqualTo(429);

        clock.advance(Duration.ofSeconds(59));
        assertThat(status(writeAs(user))).isEqualTo(429);
        clock.advance(Duration.ofSeconds(1));

        assertThat(status(writeAs(user))).isEqualTo(400);
    }

    @Test
    void anUnauthenticatedWriteIsA401AndConsumesNothing() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(status(mockMvc.perform(post("/api/v1/interview-configurations")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn())).isEqualTo(401);
        }
    }
}
