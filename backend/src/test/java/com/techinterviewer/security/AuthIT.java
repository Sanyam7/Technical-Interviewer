package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.techinterviewer.TestClock;
import com.techinterviewer.TestClockConfiguration;
import com.techinterviewer.TestJson;
import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The authentication endpoints against a real database: registration, login, refresh rotation and reuse detection,
 * logout, the cookie, and what ends up in the tables and in the log.
 */
@SpringBootTest(properties = {
    "app.cors.allowed-origins=http://localhost:5173",
    "app.rate-limit.auth-per-minute=10000"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class AuthIT {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String COOKIE = "refresh_token";
    private static final Pattern TOKEN_VALUE = Pattern.compile("[A-Za-z0-9_-]{43}");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ApplicationContext context;

    @Autowired
    TestClock clock;

    @Autowired
    RefreshTokenService refreshTokens;

    @BeforeEach
    void emptyDatabaseAndStartTheClock() {
        jdbc.execute("truncate table users cascade");
        clock.set(TestClock.START);
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult register(String email, String password, String displayName) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(TestJson.applicationMapper().writeValueAsString(
                                Map.of("email", email, "password", password, "displayName", displayName))))
                .andReturn();
    }

    private MvcResult registered(String email) throws Exception {
        MvcResult result = register(email, PASSWORD, "Ada");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return result;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(TestJson.applicationMapper().writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn();
    }

    private static MockHttpServletRequestBuilder refreshWith(String token) {
        return post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie(COOKIE, token));
    }

    private MvcResult refresh(String token) throws Exception {
        return mockMvc.perform(refreshWith(token)).andReturn();
    }

    private static String setCookie(MvcResult result) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(headers).hasSize(1);
        return headers.get(0);
    }

    /** The value of the refresh cookie that the response sets. */
    private static String refreshToken(MvcResult result) {
        Matcher matcher = Pattern.compile("^" + COOKIE + "=([^;]*)").matcher(setCookie(result));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return TestJson.applicationMapper().readTree(result.getResponse().getContentAsString());
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(body(result).get("code").asString()).isEqualTo(code);
    }

    private int familySize(String token) throws Exception {
        UUID family = jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class, sha256Hex(token));
        return jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ?", Integer.class, family);
    }

    private int liveTokensOfTheFamilyOf(String token) throws Exception {
        UUID family = jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class, sha256Hex(token));
        return jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null", Integer.class, family);
    }

    private UUID insertUser(String email, Role role, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, role, enabled) values (?, ?, ?, ?, ?, ?)",
                id, email, passwordEncoder.encode(PASSWORD), "Seeded", role.name(), enabled);
        return id;
    }

    private static String payloadOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- register

    @Test
    void registerCreatesACandidateAndAnswersWithTheAccessTokenTheUserAndOnlyTheCookieHoldsTheRefreshToken() throws Exception {
        MvcResult result = register("ada@example.com", PASSWORD, "Ada Lovelace");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(result);
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.get("user").get("email").asString()).isEqualTo("ada@example.com");
        assertThat(body.get("user").get("displayName").asString()).isEqualTo("Ada Lovelace");
        assertThat(body.get("user").get("role").asString()).isEqualTo("CANDIDATE");
        assertThat(body.get("user").propertyNames()).containsExactlyInAnyOrder("id", "email", "displayName", "role");
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "user");

        Map<String, Object> row = jdbc.queryForMap("select * from users where email = 'ada@example.com'");
        assertThat(row.get("id").toString()).isEqualTo(body.get("user").get("id").asString());
        assertThat(row.get("role")).isEqualTo("CANDIDATE");
        assertThat(row.get("enabled")).isEqualTo(true);
        assertThat(row.get("password_hash").toString()).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, row.get("password_hash").toString())).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(row.get("password_hash").toString());
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isNotNull();
    }

    @Test
    void theRefreshCookieIsHttpOnlySecureSameSiteLaxScopedToTheAuthPathAndLivesAsLongAsTheToken() throws Exception {
        MvcResult result = registered("ada@example.com");

        String cookie = setCookie(result);
        assertThat(cookie).matches("refresh_token=[A-Za-z0-9_-]{43}; Path=/api/v1/auth; Max-Age=1209600; Expires=.*; Secure; HttpOnly; SameSite=Lax");
        assertThat(TOKEN_VALUE.matcher(refreshToken(result)).matches()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(refreshToken(result));
    }

    @Test
    void onlyTheSha256OfTheRefreshTokenIsStoredAndTheFirstTokenStartsAFamily() throws Exception {
        MvcResult result = registered("ada@example.com");
        String token = refreshToken(result);

        Map<String, Object> row = jdbc.queryForMap("select * from refresh_tokens");
        assertThat(row.get("token_hash").toString()).isEqualTo(sha256Hex(token)).hasSize(64);
        assertThat(row.get("user_id").toString()).isEqualTo(body(result).get("user").get("id").asString());
        assertThat(row.get("revoked_at")).isNull();
        assertThat(row.get("replaced_by")).isNull();
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
                .isEqualTo(TestClock.START.plus(Duration.ofDays(14)));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, token)).isZero();
    }

    @Test
    void theEmailIsStoredNormalisedAndUniqueRegardlessOfCase() throws Exception {
        MvcResult first = register("  Ada.Lovelace@Example.COM ", PASSWORD, "Ada");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(first).get("user").get("email").asString()).isEqualTo("ada.lovelace@example.com");
        assertThat(jdbc.queryForObject("select email::text from users", String.class)).isEqualTo("ada.lovelace@example.com");

        for (String duplicate : List.of("ada.lovelace@example.com", "ADA.LOVELACE@EXAMPLE.COM", " Ada.Lovelace@example.com")) {
            MvcResult taken = register(duplicate, "another-password", "Someone Else");
            assertProblem(taken, 409, "EMAIL_TAKEN");
            assertThat(taken.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void registeringTheSameEmailConcurrentlyCreatesExactlyOneAccountAndTheOthersGetEmailTaken() throws Exception {
        List<MvcResult> results = concurrently(8, () -> register("race@example.com", PASSWORD, "Racer"));

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(7);
        for (MvcResult loser : results) {
            if (loser.getResponse().getStatus() == 409) {
                assertProblem(loser, 409, "EMAIL_TAKEN");
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void registrationCannotChooseARoleAndAnAdministratorIsNeverCreated() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"root@example.com\",\"password\":\"" + PASSWORD
                                + "\",\"displayName\":\"Root\",\"role\":\"ADMIN\"}"))
                .andReturn();

        assertProblem(result, 400, "MALFORMED_JSON");
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
    }

    /**
     * A password of exactly {@code totalBytes} UTF-8 bytes made of {@code unit} and a few other characters, so that it is not one
     * the policy refuses for being a single repeated character.
     */
    private static String varied(String unit, int totalBytes) {
        int width = unit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (int prefix = 3; prefix <= 8 && prefix <= totalBytes; prefix++) {
            if ((totalBytes - prefix) % width == 0) {
                return "Kq7!Zp2#".substring(0, prefix) + unit.repeat((totalBytes - prefix) / width);
            }
        }
        throw new IllegalArgumentException(unit + " cannot make " + totalBytes + " bytes");
    }

    @ParameterizedTest(name = "{0} bytes: {2}")
    @CsvSource({
        "9, 9 ASCII characters, 400",
        "10, 10 ASCII characters, 201",
        "72, 72 ASCII characters, 201",
        "73, 73 ASCII characters, 400",
    })
    void passwordLengthIsCheckedInBytesAtTheAsciiBoundaries(int bytes, String ignored, int expectedStatus) throws Exception {
        assertThat(register("p" + bytes + "@example.com", varied("x", bytes), "Ada").getResponse().getStatus())
                .isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "{0} x {1} = {2} bytes")
    @CsvSource({
        "é, 4, 400",
        "é, 5, 201",
        "é, 36, 201",
        "é, 37, 400",
        "€, 24, 201",
        "€, 25, 400",
        "😀, 18, 201",
        "😀, 19, 400",
    })
    void passwordLengthIsCheckedInBytesNotCharactersForMultiByteCharacters(String character, int count, int expectedStatus)
            throws Exception {
        int bytes = count * character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String password = varied(character, bytes);
        assertThat(register("m" + count + "@example.com", password, "Ada").getResponse().getStatus()).isEqualTo(expectedStatus);
    }

    @Test
    void aPasswordOfExactly72BytesWorksForLoginToo() throws Exception {
        String password = varied("😀", 72);
        assertThat(register("emoji@example.com", password, "Ada").getResponse().getStatus()).isEqualTo(201);

        assertThat(login("emoji@example.com", password).getResponse().getStatus()).isEqualTo(200);
        assertThat(login("emoji@example.com", password.substring(0, password.length() - 2) + "x").getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- login and /users/me

    @Test
    void theAccessTokenFromLoginIsAcceptedByAProtectedEndpointAndShowsTheStoredAccount() throws Exception {
        registered("ada@example.com");

        MvcResult login = login("ADA@example.com", PASSWORD);

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String accessToken = body(login).get("accessToken").asString();
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(body(login).get("user").get("id").asString()))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andExpect(jsonPath("$.role").value("CANDIDATE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRoleOfTheAccessTokenComesFromTheDatabase() throws Exception {
        UUID adminId = insertUser("admin@example.com", Role.ADMIN, true);
        insertUser("candidate@example.com", Role.CANDIDATE, true);

        JsonNode admin = body(login("admin@example.com", PASSWORD));
        JsonNode candidate = body(login("candidate@example.com", PASSWORD));

        assertThat(payloadOf(admin.get("accessToken").asString())).contains("\"role\":\"ADMIN\"")
                .contains("\"sub\":\"" + adminId + "\"");
        assertThat(payloadOf(candidate.get("accessToken").asString())).contains("\"role\":\"CANDIDATE\"");
        assertThat(admin.get("user").get("role").asString()).isEqualTo("ADMIN");
        assertThat(candidate.get("user").get("role").asString()).isEqualTo("CANDIDATE");
    }

    @Test
    void usersMeReadsTheCurrentAccountSoADisabledOrDeletedAccountLosesAccessAtOnce() throws Exception {
        UUID id = UUID.fromString(body(registered("ada@example.com")).get("user").get("id").asString());
        String token = TestAuth.forContext(context).token(id, Role.CANDIDATE);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("update users set enabled = false where id = ?", id);
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        jdbc.update("delete from users where id = ?", id);
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tenWrongPasswordsFromTenAddressesLockOneAccountForAWhileEvenForTheRightPassword() throws Exception {
        registered("victim@example.com");
        for (int attempt = 0; attempt < 10; attempt++) {
            int from = attempt;
            MvcResult wrong = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .with(request -> {
                        request.setRemoteAddr("203.0.113." + (from + 1));
                        return request;
                    })
                    .content("{\"email\":\"victim@example.com\",\"password\":\"not-the-password\"}")).andReturn();
            assertProblem(wrong, 401, "INVALID_CREDENTIALS");
        }

        MvcResult refused = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr("203.0.113.99");
                    return request;
                })
                .content("{\"email\":\"VICTIM@example.com\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertProblem(refused, 429, "RATE_LIMITED");
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(refused.getResponse().getHeader("Set-Cookie")).as("no session for a refused attempt").isNull();

        clock.advance(Duration.ofMinutes(16));
        assertThat(login("victim@example.com", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void loginRespondsIdenticallyForAnUnknownAddressAWrongPasswordAndADisabledAccount() throws Exception {
        registered("known@example.com");
        insertUser("disabled@example.com", Role.CANDIDATE, false);

        List<MvcResult> failures = List.of(
                login("nobody@example.com", PASSWORD),
                login("known@example.com", "wrong-password-1"),
                login("disabled@example.com", PASSWORD));

        for (MvcResult failure : failures) {
            assertProblem(failure, 401, "INVALID_CREDENTIALS");
            assertThat(failure.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
            assertThat(body(failure).get("detail").asString()).isEqualTo(body(failures.get(0)).get("detail").asString());
            assertThat(body(failure).get("title").asString()).isEqualTo(body(failures.get(0)).get("title").asString());
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void rotationDropsTheUsersExpiredTokensSoASessionThatOnlyRefreshesDoesNotAccumulateRows() throws Exception {
        MvcResult first = registered("ada@example.com");
        clock.advance(Duration.ofDays(10));
        MvcResult second = login("ada@example.com", PASSWORD);
        clock.advance(Duration.ofDays(5));

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(2);
        assertThat(refresh(refreshToken(second)).getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, sha256Hex(refreshToken(first))))
                .as("the first token expired on day 14").isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class))
                .as("the rotated token (revoked) and its successor remain").isEqualTo(2);
    }

    private Instant expiryOf(String token) throws Exception {
        return jdbc.queryForObject("select expires_at from refresh_tokens where token_hash = ?", java.sql.Timestamp.class,
                sha256Hex(token)).toInstant();
    }

    private Instant revokedAtOf(String token) throws Exception {
        java.sql.Timestamp revoked = jdbc.queryForObject(
                "select revoked_at from refresh_tokens where token_hash = ?", java.sql.Timestamp.class, sha256Hex(token));
        return revoked == null ? null : revoked.toInstant();
    }

    @Test
    void replayingARetiredTokenKeepsTheRevocationTimeOfTokensThatWereAlreadyRevoked() throws Exception {
        String first = refreshToken(registered("ada@example.com"));
        clock.advance(Duration.ofMinutes(1));
        String second = refreshToken(refresh(first));
        Instant rotatedAt = clock.instant();
        clock.advance(Duration.ofMinutes(1));

        assertProblem(refresh(first), 401, "UNAUTHENTICATED");

        assertThat(revokedAtOf(first)).as("revoked by the rotation, not again by the replay").isEqualTo(rotatedAt);
        assertThat(revokedAtOf(second)).as("revoked by the replay").isEqualTo(clock.instant());
    }

    @Test
    void aTokenIsPurgedAtTheInstantItExpires() throws Exception {
        String token = refreshToken(registered("ada@example.com"));
        clock.set(expiryOf(token).minusMillis(1));
        assertThat(refreshTokens.purgeExpired()).isZero();

        clock.set(expiryOf(token));

        assertThat(refreshTokens.purgeExpired()).isEqualTo(1);
    }

    @Test
    void aLoginDropsTheUsersTokenThatExpiresRightNowAndOnlyTheirs() throws Exception {
        String adas = refreshToken(registered("ada@example.com"));
        String graces = refreshToken(registered("grace@example.com"));
        clock.set(expiryOf(adas));

        login("ada@example.com", PASSWORD);

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, sha256Hex(adas)))
                .as("expiry is inclusive").isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, sha256Hex(graces)))
                .as("another user's expired token is left to the purge").isEqualTo(1);
    }

    @Test
    void thePurgeRemovesExpiredTokensOfAccountsThatNeverComeBack() throws Exception {
        registered("ada@example.com");
        clock.advance(Duration.ofDays(13));
        registered("grace@example.com");
        clock.advance(Duration.ofDays(2));

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(2);
        assertThat(refreshTokens.purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForList("select u.email from refresh_tokens t join users u on u.id = t.user_id", String.class))
                .containsExactly("grace@example.com");
        assertThat(refreshTokens.purgeExpired()).isZero();
    }

    @Test
    void everyLoginStartsItsOwnFamilyAndDropsTheUsersExpiredTokens() throws Exception {
        registered("ada@example.com");
        clock.advance(Duration.ofDays(15));

        MvcResult second = login("ada@example.com", PASSWORD);
        MvcResult third = login("ada@example.com", PASSWORD);

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct family_id) from refresh_tokens", Integer.class)).isEqualTo(2);
        assertThat(refresh(refreshToken(second)).getResponse().getStatus()).isEqualTo(200);
        assertThat(refresh(refreshToken(third)).getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-token", "Basic Zm9vOmJhcg==", "Bearer ", "Negotiate abc", "garbage"})
    void unknownOrForeignAuthorizationHeadersAreIgnoredOnTheAuthEndpoints(String authorization) throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", authorization)
                        .content("{\"email\":\"h@example.com\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"H\"}"))
                .andReturn();
        assertThat(registration.getResponse().getStatus()).isEqualTo(201);

        MvcResult refreshed = mockMvc.perform(refreshWith(refreshToken(registration)).header("Authorization", authorization))
                .andReturn();
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest")
                        .header("Authorization", authorization).cookie(new Cookie(COOKIE, refreshToken(refreshed))))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void anExpiredAccessTokenDoesNotBlockLoginOrRefresh() throws Exception {
        MvcResult registration = registered("ada@example.com");
        UUID id = UUID.fromString(body(registration).get("user").get("id").asString());
        String expired = TestAuth.forContext(context).expiredToken(id, Role.CANDIDATE);

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + expired)
                        .content("{\"email\":\"ada@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(refreshWith(refreshToken(registration)).header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refreshRotatesTheTokenAndTheOldOneIsRetiredAndLinkedToItsSuccessor() throws Exception {
        MvcResult registration = registered("ada@example.com");
        String first = refreshToken(registration);

        clock.advance(Duration.ofMinutes(20));
        MvcResult refreshed = refresh(first);

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        String second = refreshToken(refreshed);
        assertThat(second).isNotEqualTo(first);
        assertThat(setCookie(refreshed)).matches("refresh_token=[A-Za-z0-9_-]{43}; Path=/api/v1/auth; Max-Age=1209600; Expires=.*; Secure; HttpOnly; SameSite=Lax");
        JsonNode body = body(refreshed);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "user");
        assertThat(body.get("user").get("email").asString()).isEqualTo("ada@example.com");
        assertThat(refreshed.getResponse().getContentAsString()).doesNotContain(first).doesNotContain(second);

        Map<String, Object> retired = jdbc.queryForMap("select * from refresh_tokens where token_hash = ?", sha256Hex(first));
        Map<String, Object> successor = jdbc.queryForMap("select * from refresh_tokens where token_hash = ?", sha256Hex(second));
        assertThat(((java.sql.Timestamp) retired.get("revoked_at")).toInstant()).isEqualTo(TestClock.START.plus(Duration.ofMinutes(20)));
        assertThat(retired.get("replaced_by")).isEqualTo(successor.get("id"));
        assertThat(successor.get("revoked_at")).isNull();
        assertThat(successor.get("family_id")).isEqualTo(retired.get("family_id"));
        assertThat(((java.sql.Timestamp) successor.get("expires_at")).toInstant())
                .isEqualTo(TestClock.START.plus(Duration.ofMinutes(20)).plus(Duration.ofDays(14)));
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + body.get("accessToken").asString()))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithoutTheRequestedWithHeaderIsForbiddenAndLeavesTheTokenUsable() throws Exception {
        String token = refreshToken(registered("ada@example.com"));

        MvcResult withoutHeader = mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, token))).andReturn();
        MvcResult wrongValue = mockMvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "fetch")
                .cookie(new Cookie(COOKIE, token))).andReturn();

        assertProblem(withoutHeader, 403, "FORBIDDEN");
        assertProblem(wrongValue, 403, "FORBIDDEN");
        assertThat(refresh(token).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refreshWithoutATokenOrWithAnUnknownOrExpiredOneIs401AndClearsTheCookie() throws Exception {
        String token = refreshToken(registered("ada@example.com"));

        MvcResult noCookie = mockMvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest")).andReturn();
        MvcResult unknown = refresh("A".repeat(43));
        clock.advance(Duration.ofDays(14));
        MvcResult expired = refresh(token);

        for (MvcResult rejected : List.of(noCookie, unknown, expired)) {
            assertProblem(rejected, 401, "UNAUTHENTICATED");
            assertThat(setCookie(rejected)).startsWith("refresh_token=; Path=/api/v1/auth; Max-Age=0;").contains("HttpOnly");
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is not null", Integer.class)).isZero();
    }

    @Test
    void aTokenIsStillAcceptedOneSecondBeforeItExpiresAndRefusedAtTheInstant() throws Exception {
        String early = refreshToken(registered("early@example.com"));
        String late = refreshToken(registered("late@example.com"));

        clock.advance(Duration.ofDays(14).minusSeconds(1));
        assertThat(refresh(early).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        assertThat(refresh(late).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void presentingARotatedTokenAgainRevokesTheWholeFamilySoTheNewestTokenStopsWorking() throws Exception {
        String first = refreshToken(registered("ada@example.com"));
        String second = refreshToken(refresh(first));
        String newest = refreshToken(refresh(second));
        String otherSession = refreshToken(login("ada@example.com", PASSWORD));

        MvcResult replay = refresh(first);

        assertProblem(replay, 401, "UNAUTHENTICATED");
        assertThat(setCookie(replay)).startsWith("refresh_token=; Path=/api/v1/auth; Max-Age=0;");
        assertThat(liveTokensOfTheFamilyOf(first)).isZero();
        assertThat(familySize(first)).isEqualTo(3);
        assertProblem(refresh(newest), 401, "UNAUTHENTICATED");
        assertProblem(refresh(second), 401, "UNAUTHENTICATED");
        assertThat(refresh(otherSession).getResponse().getStatus()).as("another login is another family").isEqualTo(200);
    }

    @Test
    void aTokenRevokedByLogoutThatIsPresentedAgainStaysRefused() throws Exception {
        String token = refreshToken(registered("ada@example.com"));
        mockMvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent());

        assertProblem(refresh(token), 401, "UNAUTHENTICATED");
    }

    @Test
    void concurrentRefreshesWithTheSameTokenLetExactlyOneWinAndTheFamilyDiesAfterwards() throws Exception {
        registered("ada@example.com");
        for (int round = 0; round < 3; round++) {
            String token = refreshToken(login("ada@example.com", PASSWORD));

            List<MvcResult> results = concurrently(4, () -> refresh(token));

            List<MvcResult> winners = results.stream().filter(r -> r.getResponse().getStatus() == 200).toList();
            List<MvcResult> losers = results.stream().filter(r -> r.getResponse().getStatus() == 401).toList();
            assertThat(winners).hasSize(1);
            assertThat(losers).hasSize(3);
            for (MvcResult loser : losers) {
                assertProblem(loser, 401, "UNAUTHENTICATED");
            }
            String winnersToken = refreshToken(winners.get(0));
            assertThat(liveTokensOfTheFamilyOf(token)).isZero();
            assertProblem(refresh(winnersToken), 401, "UNAUTHENTICATED");
            assertThat(familySize(token)).isEqualTo(2);
        }
    }

    @Test
    void refreshReadsTheAccountAgainSoTheNewAccessTokenCarriesTheCurrentRole() throws Exception {
        MvcResult registration = registered("ada@example.com");
        UUID id = UUID.fromString(body(registration).get("user").get("id").asString());
        jdbc.update("update users set role = 'ADMIN' where id = ?", id);

        MvcResult refreshed = refresh(refreshToken(registration));

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        assertThat(payloadOf(body(refreshed).get("accessToken").asString())).contains("\"role\":\"ADMIN\"");
        assertThat(body(refreshed).get("user").get("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    void refreshForADisabledAccountIs401AndRevokesItsFamily() throws Exception {
        MvcResult registration = registered("ada@example.com");
        String token = refreshToken(registration);
        jdbc.update("update users set enabled = false");

        assertProblem(refresh(token), 401, "UNAUTHENTICATED");

        assertThat(liveTokensOfTheFamilyOf(token)).isZero();
        jdbc.update("update users set enabled = true");
        assertProblem(refresh(token), 401, "UNAUTHENTICATED");
    }

    @Test
    void refreshForADeletedAccountIs401() throws Exception {
        String token = refreshToken(registered("ada@example.com"));
        jdbc.update("delete from users");

        assertProblem(refresh(token), 401, "UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logoutRevokesTheWholeFamilyClearsTheCookieAndNoLongerAllowsRefreshing() throws Exception {
        String first = refreshToken(registered("ada@example.com"));
        String second = refreshToken(refresh(first));
        String otherSession = refreshToken(login("ada@example.com", PASSWORD));

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest")
                .cookie(new Cookie(COOKIE, second))).andReturn();

        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(logout.getResponse().getContentAsString()).isEmpty();
        assertThat(setCookie(logout)).startsWith("refresh_token=; Path=/api/v1/auth; Max-Age=0;").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(liveTokensOfTheFamilyOf(second)).isZero();
        assertProblem(refresh(second), 401, "UNAUTHENTICATED");
        assertThat(refresh(otherSession).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logoutIsIdempotentWithoutACookieAndWithAnUnknownOne() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest"),
                post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie(COOKIE, "unknown-value")))) {
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(setCookie(result)).startsWith("refresh_token=; Path=/api/v1/auth; Max-Age=0;");
        }
    }

    @Test
    void logoutWithoutTheRequestedWithHeaderIsForbiddenAndRevokesNothing() throws Exception {
        String token = refreshToken(registered("ada@example.com"));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, token))).andReturn();

        assertProblem(result, 403, "FORBIDDEN");
        assertThat(liveTokensOfTheFamilyOf(token)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CORS

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "refresh", "logout"})
    void preflightForTheAuthEndpointsFromTheAllowedOriginAllowsCredentialsAndTheCsrfHeader(String endpoint) throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/auth/" + endpoint)
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-requested-with"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("x-requested-with");
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods")).contains("POST");
    }

    @Test
    void preflightForTheRefreshEndpointFromAnotherOriginIsRefusedSoAForeignScriptCannotAddTheHeader() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/auth/refresh")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-requested-with"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void theActualAuthResponseCarriesTheCorsHeadersForTheAllowedOrigin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost:5173")
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    // ---------------------------------------------------------------- logs

    @Test
    void noLogLineContainsAPasswordATokenOrACookieValue() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        List<String> secrets = new ArrayList<>();
        try {
            String password = "S3cret-Passw0rd-for-the-log-test";
            MvcResult registration = register("log@example.com", password, "Logger");
            secrets.add(password);
            secrets.add(body(registration).get("accessToken").asString());
            String first = refreshToken(registration);
            secrets.add(first);
            MvcResult second = refresh(first);
            secrets.add(refreshToken(second));
            secrets.add(body(second).get("accessToken").asString());
            login("log@example.com", "Wr0ng-Passw0rd-for-the-log-test");
            secrets.add("Wr0ng-Passw0rd-for-the-log-test");
            refresh(first);
            register("log@example.com", password, "Logger");
            mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"bad\",\"password\":\"Short-Passw0rd-leak\",\"displayName\":\"x\"}")).andReturn();
            secrets.add("Short-Passw0rd-leak");
            MvcResult login = login("log@example.com", password);
            secrets.add(body(login).get("accessToken").asString());
            secrets.add(refreshToken(login));
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }

        List<String> lines = appender.list.stream().map(AuthIT::render).toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines).anyMatch(line -> line.contains("Retired refresh token presented again"));
        for (String secret : secrets) {
            assertThat(lines).as("a log line contains a secret of length %d", secret.length())
                    .noneMatch(line -> line.contains(secret));
        }
    }

    private static String render(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return event.getFormattedMessage() + (throwable == null ? "" : ThrowableProxyUtil.asString(throwable));
    }
}
