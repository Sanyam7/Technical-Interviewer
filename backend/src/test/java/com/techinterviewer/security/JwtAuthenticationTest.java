package com.techinterviewer.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestClock;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.Role;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * The real security filter chain, the real JWT decoder and the real problem handlers in front of the test-only
 * endpoints, without a database. Time is a controllable clock, so expiry is tested at the exact boundary.
 */
@WebMvcTest(SecurityTestController.class)
@ActiveProfiles(SecurityTestController.PROFILE)
@WithApplicationSecurity
class JwtAuthenticationTest {

    private static final String SECURED = "/api/v1/test/secured";
    private static final String ADMIN = "/api/v1/admin/test";
    private static final String ME = "/api/v1/test/me";
    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0e");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @Autowired
    AppProperties properties;

    @Autowired
    JwtService jwtService;

    TestAuth auth;

    @BeforeEach
    void setUp(@Autowired ApplicationContext context) {
        auth = TestAuth.forContext(context);
    }

    private ResultActions getWith(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private static ResultActions unauthenticated(ResultActions result) throws Exception {
        return result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value(ProblemDetailAuthenticationEntryPoint.GENERIC_DETAIL));
    }

    // ---- who may call what ----------------------------------------------------------------------------

    @Test
    void validCandidateTokenOpensASecuredEndpoint() throws Exception {
        getWith(SECURED, auth.token(USER, Role.CANDIDATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void requestWithoutATokenIsRefusedWithABearerChallenge() throws Exception {
        unauthenticated(mockMvc.perform(get(SECURED)))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void adminEndpointsAreForbiddenToCandidatesAndOpenToAdmins() throws Exception {
        unauthenticated(mockMvc.perform(get(ADMIN)));
        getWith(ADMIN, auth.token(USER, Role.CANDIDATE))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").exists());
        getWith(ADMIN, auth.token(USER, Role.ADMIN)).andExpect(status().isOk());
    }

    @Test
    void adminRuleAppliesToEverythingUnderAdmin() throws Exception {
        String candidate = auth.token(USER, Role.CANDIDATE);
        for (String path : new String[] {"/api/v1/admin", "/api/v1/admin/anything", "/api/v1/admin/knowledge/documents"}) {
            getWith(path, candidate).andExpect(status().isForbidden());
        }
    }

    @Test
    void theRoleComesFromTheSignedClaimAndAnEditedPayloadIsRefused() throws Exception {
        unauthenticated(getWith(ADMIN, auth.roleEscalatedToken(USER)));
    }

    @Test
    void everyOtherApiPathNeedsAuthenticationBeforeItIsEvenLookedUp() throws Exception {
        unauthenticated(mockMvc.perform(get("/api/v1/no/such/thing")));
        getWith("/api/v1/no/such/thing", auth.token(USER, Role.CANDIDATE)).andExpect(status().isNotFound());
    }

    @Test
    void pathsOutsideTheApiAreDeniedEvenToAdmins() throws Exception {
        unauthenticated(mockMvc.perform(get("/internal/secret")));
        getWith("/internal/secret", auth.token(USER, Role.ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---- public endpoints ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void authEndpointsAreReachableWithoutATokenAndAnExpiredOneDoesNotGetInTheWay(String path) throws Exception {
        // No controller exists in this slice: 404 proves the request passed security (401 would mean it did not).
        mockMvc.perform(post(path)).andExpect(status().isNotFound());
        mockMvc.perform(post(path).header("Authorization", "Bearer " + auth.expiredToken(USER, Role.CANDIDATE)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(path).header("Authorization", "Bearer garbage"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyThePostOfTheAuthEndpointsIsPublic() throws Exception {
        unauthenticated(mockMvc.perform(get("/api/v1/auth/login")));
        unauthenticated(mockMvc.perform(post("/api/v1/auth/me")));
        unauthenticated(mockMvc.perform(post("/api/v1/auth/login/extra")));
    }

    @Test
    void catalogReadsArePublicAndWritesAreNot() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/skills")).andExpect(status().isNotFound());
        mockMvc.perform(head("/api/v1/catalog/skills/java/topics")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/catalog/skills").header("Authorization", "Bearer garbage")).andExpect(status().isNotFound());
        unauthenticated(mockMvc.perform(post("/api/v1/catalog/skills")));
    }

    // ---- token expiry -----------------------------------------------------------------------------------

    @Test
    void tokenIsAcceptedUntilItsExpiryInstantAndRefusedFromThenOn() throws Exception {
        String token = auth.token(USER, Role.CANDIDATE);
        Duration ttl = properties.security().jwt().accessTokenTtl();

        clock.advance(ttl.minusSeconds(1));
        getWith(SECURED, token).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(1));
        getWith(SECURED, token)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.detail").value(ProblemDetailAuthenticationEntryPoint.EXPIRED_DETAIL))
                .andExpect(header().string("WWW-Authenticate", containsString("error=\"invalid_token\"")))
                .andExpect(header().string("WWW-Authenticate", containsString("expired")));
    }

    @Test
    void anExpiredTokenIsAlsoRefusedOnAdminEndpoints() throws Exception {
        getWith(ADMIN, auth.expiredToken(USER, Role.ADMIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void expiryIsReportedOnlyWhenItIsTheOnlyProblemWithTheToken() throws Exception {
        String expiredAndForeign = auth.custom(USER, Role.CANDIDATE, claims -> {
            claims.issuer("https://evil.example");
            claims.issueTime(Date.from(clock.instant().minusSeconds(7200)));
            claims.expirationTime(Date.from(clock.instant().minusSeconds(3600)));
        });

        unauthenticated(getWith(SECURED, expiredAndForeign))
                .andExpect(header().string("WWW-Authenticate", not(containsString("expired"))));
    }

    // ---- tokens that must never be accepted ------------------------------------------------------------

    static Stream<Arguments> forgedTokens() {
        UUID id = USER;
        return Stream.<Arguments>of(
                forged("alg none", a -> a.algNoneToken(id, Role.ADMIN)),
                forged("RS256 signature", a -> a.rs256Token(id, Role.ADMIN)),
                forged("HS384 with the right key", a -> a.hs384Token(id, Role.ADMIN)),
                forged("another issuer", a -> a.otherIssuerToken(id, Role.ADMIN)),
                forged("another signing key", a -> a.otherKeyToken(id, Role.ADMIN)),
                forged("tampered signature", a -> a.tamperedSignatureToken(id, Role.ADMIN)),
                forged("edited payload", a -> a.roleEscalatedToken(id)),
                forged("no role claim", a -> a.withoutRoleToken(id)),
                forged("unknown role", a -> a.withRoleClaimToken(id, "SUPERUSER")),
                forged("lower-case role", a -> a.withRoleClaimToken(id, "admin")),
                forged("role with a prefix", a -> a.withRoleClaimToken(id, "ROLE_ADMIN")),
                forged("no subject", a -> a.withoutSubjectToken(Role.ADMIN)),
                forged("subject that is not a UUID", a -> a.withSubjectToken("admin", Role.ADMIN)),
                forged("no expiry", a -> a.withoutExpiryToken(id, Role.ADMIN)),
                forged("empty string", a -> ""),
                forged("garbage", a -> "not-a-jwt"),
                forged("two segments", a -> "aaa.bbb"));
    }

    private static Arguments forged(String name, Function<TestAuth, String> token) {
        return Arguments.of(name, token);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forgedTokens")
    void forgedAndMalformedTokensAreRefusedAsUnauthenticated(String name, Function<TestAuth, String> token)
            throws Exception {
        String value = token.apply(auth);
        if (value.isEmpty()) {
            unauthenticated(mockMvc.perform(get(ADMIN).header("Authorization", "Bearer")));
            return;
        }
        unauthenticated(getWith(ADMIN, value))
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(content().string(not(containsString("signature"))))
                .andExpect(content().string(not(containsString("algorithm"))));
        unauthenticated(getWith(SECURED, value));
    }

    @ParameterizedTest(name = "Authorization: {0}")
    @ValueSource(strings = {"Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "Token abc", "Bearer a b", "Bearer !!!", "Bearer   "})
    void malformedAuthorizationHeadersAreRefusedAsUnauthenticated(String header) throws Exception {
        unauthenticated(mockMvc.perform(get(SECURED).header("Authorization", header)));
    }

    @Test
    void aValidTokenFollowedByGarbageIsNotAValidHeader() throws Exception {
        unauthenticated(mockMvc.perform(get(SECURED).header("Authorization", "Bearer " + auth.token(USER, Role.CANDIDATE) + " extra")));
    }

    // ---- where the token may come from ------------------------------------------------------------------------

    @Test
    void aTokenInTheQueryStringIsIgnored() throws Exception {
        String token = auth.token(USER, Role.ADMIN);

        unauthenticated(mockMvc.perform(get(SECURED).param("access_token", token)));
        unauthenticated(mockMvc.perform(get(SECURED).param("token", token)));
    }

    @Test
    void aTokenInACookieIsIgnored() throws Exception {
        String token = auth.token(USER, Role.ADMIN);

        unauthenticated(mockMvc.perform(get(SECURED).cookie(new Cookie("access_token", token))));
        unauthenticated(mockMvc.perform(get(SECURED).cookie(new Cookie("refresh_token", token), new Cookie("Authorization", token))));
    }

    @Test
    void aTokenInAFormBodyIsIgnored() throws Exception {
        String token = auth.token(USER, Role.ADMIN);

        unauthenticated(mockMvc.perform(post("/api/v1/test/echo")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("access_token", token)));
    }

    // ---- @CurrentUser -------------------------------------------------------------------------------------------

    @Test
    void currentUserIsTheAccountOfTheVerifiedToken() throws Exception {
        UUID admin = UUID.randomUUID();

        getWith(ME, auth.token(USER, Role.CANDIDATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER.toString()))
                .andExpect(jsonPath("$.role").value("CANDIDATE"));
        getWith(ME, auth.token(admin, Role.ADMIN))
                .andExpect(jsonPath("$.id").value(admin.toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void currentUserCannotBeChosenByTheClient() throws Exception {
        MockHttpServletRequestBuilder request = get(ME)
                .header("Authorization", "Bearer " + auth.token(USER, Role.CANDIDATE))
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN")
                .param("id", UUID.randomUUID().toString())
                .param("role", "ADMIN");

        mockMvc.perform(request)
                .andExpect(jsonPath("$.id").value(USER.toString()))
                .andExpect(jsonPath("$.role").value("CANDIDATE"));
    }

    @Test
    void currentUserRefusesAnAuthenticationThatIsNotAnAccessToken() throws Exception {
        // A principal that Spring Security accepts but that was not proven by an access token.
        mockMvc.perform(get(ME).with(user("someone").roles("CANDIDATE")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tokensHeadersAndErrorsAreNeverCached() throws Exception {
        getWith(SECURED, auth.token(USER, Role.CANDIDATE))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mockMvc.perform(get(SECURED))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
