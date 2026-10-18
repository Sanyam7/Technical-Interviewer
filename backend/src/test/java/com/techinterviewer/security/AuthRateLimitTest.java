package com.techinterviewer.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestClock;
import com.techinterviewer.security.dto.LoginRequest;
import com.techinterviewer.security.dto.RegisterRequest;
import com.techinterviewer.security.dto.TokenResponse;
import com.techinterviewer.user.Role;
import com.techinterviewer.user.dto.UserResponse;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The per-client-address limit of the auth endpoints, through the real filter chain. The allowance is 3 per minute
 * (not the default of 10), which proves the configuration is what limits.
 */
@WebMvcTest(AuthController.class)
@WithApplicationSecurity
@Import(RefreshCookieFactory.class)
@TestPropertySource(properties = "app.rate-limit.auth-per-minute=3")
class AuthRateLimitTest {

    private static final UserResponse ADA = new UserResponse(UUID.randomUUID(), "ada@example.com", "Ada", Role.CANDIDATE);
    private static final AuthSession SESSION = new AuthSession(
            new TokenResponse("access.jwt.value", "Bearer", 900, ADA), new IssuedRefreshToken("r", Instant.parse("2026-10-19T12:00:00Z")));
    private static final String LOGIN_BODY = "{\"email\":\"ada@example.com\",\"password\":\"x\"}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @MockitoBean
    AuthService auth;

    @BeforeEach
    void startANewWindowAndStubTheService() {
        clock.advance(Duration.ofMinutes(5));
        when(auth.login(any(LoginRequest.class))).thenReturn(SESSION);
        when(auth.register(any(RegisterRequest.class))).thenReturn(SESSION);
        when(auth.refresh(any())).thenReturn(SESSION);
    }

    private ResultActions login(String address) throws Exception {
        return mockMvc.perform(withAddress(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY), address));
    }

    private static MockHttpServletRequestBuilder withAddress(MockHttpServletRequestBuilder request, String address) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    private void exhaust(String address) throws Exception {
        for (int i = 0; i < 3; i++) {
            login(address).andExpect(status().isOk());
        }
    }

    @Test
    void theFourthRequestWithinTheMinuteIsAnswered429WithRetryAfterAndAProblemBody() throws Exception {
        exhaust("198.51.100.1");

        login("198.51.100.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void theAllowanceIsPerAddressSoAnotherClientIsNotAffected() throws Exception {
        exhaust("198.51.100.1");
        login("198.51.100.1").andExpect(status().isTooManyRequests());

        login("198.51.100.2").andExpect(status().isOk());
    }

    @Test
    void aForwardedForHeaderCannotBeUsedToPickAnotherAddress() throws Exception {
        exhaust("198.51.100.1");

        mockMvc.perform(withAddress(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)
                        .header("X-Forwarded-For", "203.0.113.77").header("X-Real-IP", "203.0.113.78").header("Forwarded", "for=203.0.113.79"),
                        "198.51.100.1"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theAllowanceIsBackWhenTheMinuteHasPassedAndNotBefore() throws Exception {
        exhaust("198.51.100.1");

        clock.advance(Duration.ofSeconds(59));
        login("198.51.100.1").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        clock.advance(Duration.ofSeconds(1));
        login("198.51.100.1").andExpect(status().isOk());
    }

    @Test
    void retryAfterCountsDownAsTimePasses() throws Exception {
        exhaust("198.51.100.1");

        clock.advance(Duration.ofSeconds(25));

        login("198.51.100.1").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "35"));
    }

    @Test
    void registerLoginAndRefreshShareOneAllowancePerAddress() throws Exception {
        String address = "198.51.100.3";
        mockMvc.perform(withAddress(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ada@example.com\",\"password\":\"correct-horse-battery\",\"displayName\":\"Ada\"}"), address))
                .andExpect(status().isCreated());
        login(address).andExpect(status().isOk());
        mockMvc.perform(withAddress(post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                .cookie(new Cookie("refresh_token", "c")), address)).andExpect(status().isOk());

        login(address).andExpect(status().isTooManyRequests());
        mockMvc.perform(withAddress(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"), address))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(withAddress(post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest"), address))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void logoutAndOtherMethodsAreNotLimited() throws Exception {
        exhaust("198.51.100.4");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(withAddress(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest"), "198.51.100.4"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(withAddress(get("/api/v1/auth/login"), "198.51.100.4")).andExpect(status().is4xxClientError())
                    .andExpect(status().is(not(429)));
        }
    }

    @Test
    void requestsThatFailValidationOrTheCsrfHeaderCheckStillCountTowardsTheLimit() throws Exception {
        String address = "198.51.100.5";
        mockMvc.perform(withAddress(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"), address))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withAddress(post("/api/v1/auth/refresh"), address)).andExpect(status().isForbidden());
        mockMvc.perform(withAddress(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("garbage"), address))
                .andExpect(status().isBadRequest());

        login(address).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitAnswersBeforeTheBodyIsEvenRead() throws Exception {
        exhaust("198.51.100.6");

        mockMvc.perform(withAddress(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("not json"), "198.51.100.6"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aLimitedResponseStillCarriesTheCorsHeadersSoABrowserCanReadIt() throws Exception {
        exhaust("198.51.100.7");

        mockMvc.perform(withAddress(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)
                        .header("Origin", "http://localhost:5173"), "198.51.100.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void preflightRequestsAreNeverCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(withAddress(options("/api/v1/auth/login")
                            .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST"), "198.51.100.8"))
                    .andExpect(status().isOk());
        }

        login("198.51.100.8").andExpect(status().isOk());
    }
}
