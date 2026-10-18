package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.security.dto.LoginRequest;
import com.techinterviewer.security.dto.RegisterRequest;
import com.techinterviewer.security.dto.TokenResponse;
import com.techinterviewer.user.Role;
import com.techinterviewer.user.dto.UserResponse;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The HTTP contract of the auth endpoints in front of a stubbed {@link AuthService}: no database. */
@WebMvcTest(AuthController.class)
@WithApplicationSecurity
@Import(RefreshCookieFactory.class)
class AuthControllerTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String PASSWORD = "correct-horse-battery";
    private static final UserResponse ADA =
            new UserResponse(UUID.fromString("0192f5c2-8a3e-7c1d-9b1e-3f4a5b6c7d8e"), "ada@example.com", "Ada", Role.CANDIDATE);
    private static final AuthSession SESSION = new AuthSession(
            new TokenResponse("access.jwt.value", "Bearer", 900, ADA),
            new IssuedRefreshToken("refresh-secret-value", Instant.parse("2026-10-19T12:00:00Z")));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @MockitoBean
    AuthService auth;

    /** Every test calls from the same address: a new window gives each one its full allowance (see AuthRateLimitTest). */
    @BeforeEach
    void startANewRateLimitWindow() {
        clock.advance(Duration.ofMinutes(2));
    }

    private static String registerJson(String email, String password, String displayName) {
        return "{\"email\":" + quote(email) + ",\"password\":" + quote(password) + ",\"displayName\":" + quote(displayName) + "}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder ajax(MockHttpServletRequestBuilder request) {
        return request.header("X-Requested-With", "XMLHttpRequest");
    }

    // ---- register ----------------------------------------------------------------------------------------

    @Test
    void registerAnswers201WithTheTokensTheUserAndTheRefreshCookieButNoRefreshTokenInTheBody() throws Exception {
        when(auth.register(any(RegisterRequest.class))).thenReturn(SESSION);

        mockMvc.perform(json(post(REGISTER), registerJson("ada@example.com", PASSWORD, "Ada")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value(ADA.id().toString()))
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.user.displayName").value("Ada"))
                .andExpect(jsonPath("$.user.role").value("CANDIDATE"))
                .andExpect(content().string(not(containsString("refresh-secret-value"))))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(header().string("Set-Cookie", containsString("refresh_token=refresh-secret-value;")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void registerHandsTheServiceTheNormalisedRequest() throws Exception {
        when(auth.register(any(RegisterRequest.class))).thenReturn(SESSION);

        mockMvc.perform(json(post(REGISTER), registerJson("  Ada@EXAMPLE.com ", PASSWORD, "  Ada  "))).andExpect(status().isCreated());

        ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(auth).register(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("ada@example.com");
        assertThat(captor.getValue().displayName()).isEqualTo("Ada");
        assertThat(captor.getValue().password()).isEqualTo(PASSWORD);
    }

    @Test
    void registerRejectsInvalidFieldsWithTheirNamesAndNeverEchoesThePassword() throws Exception {
        mockMvc.perform(json(post(REGISTER), registerJson("not-an-email", "short", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("displayName", "email", "password")))
                .andExpect(content().string(not(containsString("short"))));

        verifyNoInteractions(auth);
    }

    @Test
    void registerRejectsAPasswordOf73BytesAndAcceptsOneOf72() throws Exception {
        when(auth.register(any(RegisterRequest.class))).thenReturn(SESSION);

        mockMvc.perform(json(post(REGISTER), registerJson("ada@example.com", "p".repeat(73), "Ada"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        mockMvc.perform(json(post(REGISTER), registerJson("ada@example.com", "p".repeat(72), "Ada"))).andExpect(status().isCreated());
    }

    @Test
    void registerCannotCreateAnAdministratorBecauseARoleIsNotAcceptedAtAll() throws Exception {
        String body = "{\"email\":\"ada@example.com\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Ada\",\"role\":\"ADMIN\"}";

        mockMvc.perform(json(post(REGISTER), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));

        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null", "{\"email\":\"a@b.co\"", "not json"})
    void registerRejectsAMissingOrMalformedBody(String body) throws Exception {
        mockMvc.perform(json(post(REGISTER), body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(auth);
    }

    @Test
    void registerRejectsAWrongContentType() throws Exception {
        mockMvc.perform(post(REGISTER).contentType(MediaType.TEXT_PLAIN).content(registerJson("ada@example.com", PASSWORD, "Ada")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aTakenEmailIsA409WithItsOwnCode() throws Exception {
        when(auth.register(any(RegisterRequest.class))).thenThrow(new ApiException(ErrorCode.EMAIL_TAKEN, "taken"));

        mockMvc.perform(json(post(REGISTER), registerJson("ada@example.com", PASSWORD, "Ada")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ---- login -------------------------------------------------------------------------------------------

    @Test
    void loginAnswers200WithTheTokensAndTheRefreshCookie() throws Exception {
        when(auth.login(any(LoginRequest.class))).thenReturn(SESSION);

        mockMvc.perform(json(post(LOGIN), "{\"email\":\" ADA@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(header().string("Set-Cookie", containsString("refresh_token=refresh-secret-value;")));

        ArgumentCaptor<LoginRequest> captor = ArgumentCaptor.forClass(LoginRequest.class);
        verify(auth).login(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("ada@example.com");
    }

    @Test
    void invalidCredentialsAre401WithoutACookie() throws Exception {
        when(auth.login(any(LoginRequest.class))).thenThrow(new ApiException(ErrorCode.INVALID_CREDENTIALS, AuthService.INVALID_CREDENTIALS_DETAIL));

        mockMvc.perform(json(post(LOGIN), "{\"email\":\"ada@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void loginRejectsAMissingPasswordAndAnOverlongOne() throws Exception {
        mockMvc.perform(json(post(LOGIN), "{\"email\":\"ada@example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("password"));
        mockMvc.perform(json(post(LOGIN), "{\"email\":\"ada@example.com\",\"password\":\"" + "p".repeat(73) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("password"));

        verifyNoInteractions(auth);
    }

    // ---- refresh -----------------------------------------------------------------------------------------

    @Test
    void refreshWithoutTheRequestedWithHeaderIsForbiddenAndDoesNotTouchTheToken() throws Exception {
        mockMvc.perform(post(REFRESH).cookie(new Cookie("refresh_token", "refresh-secret-value")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "xmlhttprequest", "XMLHttpRequest2", "fetch", ""})
    void refreshAndLogoutDemandTheExactHeaderValue(String value) throws Exception {
        for (String path : new String[] {REFRESH, LOGOUT}) {
            mockMvc.perform(post(path).header("X-Requested-With", value).cookie(new Cookie("refresh_token", "x")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        verifyNoInteractions(auth);
    }

    @Test
    void refreshPassesTheCookieValueToTheServiceAndRotatesTheCookie() throws Exception {
        when(auth.refresh("old-refresh-value")).thenReturn(SESSION);

        mockMvc.perform(ajax(post(REFRESH)).cookie(new Cookie("refresh_token", "old-refresh-value")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
                .andExpect(header().string("Set-Cookie", containsString("refresh_token=refresh-secret-value;")))
                .andExpect(content().string(not(containsString("refresh-secret-value"))));
    }

    @Test
    void refreshOnlyReadsTheConfiguredCookieNotABodyOrAParameter() throws Exception {
        when(auth.refresh(any())).thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "no"));

        mockMvc.perform(ajax(post(REFRESH).param("refresh_token", "from-param").header("Refresh-Token", "from-header")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"from-body\"}")))
                .andExpect(status().isUnauthorized());

        verify(auth).refresh(null);
    }

    @Test
    void aRefusedRefreshIs401AndClearsTheCookie() throws Exception {
        when(auth.refresh("stale")).thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, AuthService.REFRESH_REJECTED_DETAIL));

        mockMvc.perform(ajax(post(REFRESH)).cookie(new Cookie("refresh_token", "stale")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("Set-Cookie", containsString("refresh_token=;")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    // ---- logout ------------------------------------------------------------------------------------------

    @Test
    void logoutRevokesTheCookieTokenAndClearsTheCookie() throws Exception {
        mockMvc.perform(ajax(post(LOGOUT)).cookie(new Cookie("refresh_token", "live-token")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Set-Cookie", containsString("refresh_token=;")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));

        verify(auth).logout("live-token");
    }

    @Test
    void logoutWithoutACookieIsStill204() throws Exception {
        mockMvc.perform(ajax(post(LOGOUT)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(auth).logout(null);
    }

    // ---- an Authorization header means nothing on these public endpoints ----------------------------------

    static Stream<String> foreignAuthorizationHeaders() {
        return Stream.of("Bearer garbage", "Bearer ", "Bearer", "Basic dXNlcjpwYXNz", "Token abc", "Bearer a.b.c");
    }

    @ParameterizedTest
    @MethodSource("foreignAuthorizationHeaders")
    void aStrangeAuthorizationHeaderNeverBlocksTheAuthEndpoints(String authorization) throws Exception {
        when(auth.login(any(LoginRequest.class))).thenReturn(SESSION);
        when(auth.register(any(RegisterRequest.class))).thenReturn(SESSION);
        when(auth.refresh("c")).thenReturn(SESSION);

        mockMvc.perform(json(post(LOGIN), "{\"email\":\"ada@example.com\",\"password\":\"x\"}").header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(json(post(REGISTER), registerJson("ada@example.com", PASSWORD, "Ada")).header("Authorization", authorization))
                .andExpect(status().isCreated());
        mockMvc.perform(ajax(post(REFRESH)).cookie(new Cookie("refresh_token", "c")).header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(ajax(post(LOGOUT)).header("Authorization", authorization)).andExpect(status().isNoContent());
    }

    @Test
    void anExpiredOrForgedAccessTokenNeverBlocksTheAuthEndpoints(@Autowired ApplicationContext context)
            throws Exception {
        TestAuth tokens = TestAuth.forContext(context);
        when(auth.refresh("c")).thenReturn(SESSION);
        UUID id = UUID.randomUUID();

        for (String token : new String[] {tokens.expiredToken(id, Role.CANDIDATE), tokens.tamperedSignatureToken(id, Role.ADMIN),
                tokens.algNoneToken(id, Role.ADMIN)}) {
            mockMvc.perform(ajax(post(REFRESH)).cookie(new Cookie("refresh_token", "c")).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void theAuthEndpointsAreNotReachableWithOtherMethods() throws Exception {
        mockMvc.perform(get(LOGIN)).andExpect(status().is4xxClientError());
        mockMvc.perform(put(REGISTER)).andExpect(status().is4xxClientError());
        verifyNoInteractions(auth);
    }
}
