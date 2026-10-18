package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.config.AppProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RefreshCookieFactoryTest {

    private static final IssuedRefreshToken TOKEN = new IssuedRefreshToken("the-secret-value", Instant.parse("2026-10-19T12:00:00Z"));

    private static RefreshCookieFactory factory(String name, boolean secure, Duration ttl, String path) {
        return factory(new AppProperties.RefreshCookie(name, secure, ttl, path));
    }

    private static RefreshCookieFactory factory(AppProperties.RefreshCookie cookie) {
        AppProperties properties = new AppProperties(
                new AppProperties.Cors(List.of("http://localhost:5173")),
                new AppProperties.Security(
                        new AppProperties.Jwt(null, false, "tech-interviewer", Duration.ofMinutes(15)),
                        cookie),
                new AppProperties.RateLimit(10, 20, 10, 60, 10, 10, 10, java.time.Duration.ofMinutes(15)),
                new AppProperties.Interview(Duration.ofSeconds(30), 2, 4),
                new AppProperties.Limits(262_144, 16_384, 2_162_688));
        return new RefreshCookieFactory(properties);
    }

    /** Splits a Set-Cookie header into its attributes; flags have an empty value. */
    private static Map<String, String> attributes(String setCookie) {
        return Arrays.stream(setCookie.split(";\\s*"))
                .skip(1)
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(part -> part[0].toLowerCase(), part -> part.length > 1 ? part[1] : ""));
    }

    @Test
    void theIssuedCookieCarriesTheTokenWithTheConfiguredNameAndTheSecurityAttributes() {
        String header = factory("refresh_token", true, Duration.ofDays(14), "/api/v1/auth").issue(TOKEN).toString();

        assertThat(header).startsWith("refresh_token=the-secret-value;");
        assertThat(attributes(header))
                .containsEntry("httponly", "")
                .containsEntry("secure", "")
                .containsEntry("samesite", "Lax")
                .containsEntry("path", "/api/v1/auth")
                .containsEntry("max-age", "1209600")
                .containsKey("expires")
                .hasSize(6);
    }

    @Test
    void theLifetimeFollowsTheConfiguredTtlAndTheNameAndPathFollowTheConfiguration() {
        String header = factory("sid", true, Duration.ofHours(2), "/auth").issue(TOKEN).toString();

        assertThat(header).startsWith("sid=the-secret-value;");
        assertThat(attributes(header)).containsEntry("max-age", "7200").containsEntry("path", "/auth");
    }

    @Test
    void withoutTheSecureSettingTheCookieHasNoSecureAttributeButStaysHttpOnlyAndLax() {
        String header = factory("refresh_token", false, Duration.ofDays(14), "/api/v1/auth").issue(TOKEN).toString();

        assertThat(attributes(header)).doesNotContainKey("secure").containsKey("httponly").containsEntry("samesite", "Lax");
    }

    @Test
    void theClearingCookieHasTheSameNameAndPathAndExpiresImmediately() {
        String header = factory("refresh_token", true, Duration.ofDays(14), "/api/v1/auth").clear().toString();

        assertThat(header).startsWith("refresh_token=;");
        assertThat(attributes(header))
                .containsEntry("max-age", "0")
                .containsEntry("path", "/api/v1/auth")
                .containsEntry("samesite", "Lax")
                .containsKey("httponly")
                .containsKey("secure");
    }

    @Test
    void readFindsTheCookieByItsConfiguredName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"), new Cookie("sid", "value-1"));

        assertThat(factory("sid", true, Duration.ofDays(1), "/").read(request)).isEqualTo("value-1");
        assertThat(factory("refresh_token", true, Duration.ofDays(1), "/").read(request)).isNull();
    }

    @Test
    void readIgnoresAnEmptyOrBlankCookieAndRequestsWithoutCookies() {
        RefreshCookieFactory factory = factory("refresh_token", true, Duration.ofDays(1), "/");
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setCookies(new Cookie("refresh_token", ""), new Cookie("refresh_token", "  "));

        assertThat(factory.read(blank)).isNull();
        assertThat(factory.read(new MockHttpServletRequest())).isNull();
    }

    @Test
    void readTakesTheFirstNonBlankValueWhenTheCookieIsSentTwice() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", ""), new Cookie("refresh_token", "real"));

        assertThat(factory("refresh_token", true, Duration.ofDays(1), "/").read(request)).isEqualTo("real");
    }

    @Test
    void theTokenNeverAppearsInThePrintedForm() {
        assertThat(TOKEN.toString()).doesNotContain("the-secret-value");
    }

    @Test
    void aDeploymentOnTwoSitesSendsTheCookieWithSameSiteNone() {
        String header = factory(new AppProperties.RefreshCookie("refresh_token", true, Duration.ofDays(14), "/api/v1/auth", "None"))
                .issue(TOKEN).toString();

        assertThat(attributes(header)).containsEntry("samesite", "None").containsEntry("secure", "").containsEntry("httponly", "");
    }

    @Test
    void sameSiteNoneWithoutSecureIsRefusedBecauseBrowsersWouldDropTheCookie() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AppProperties.RefreshCookie("refresh_token", false, Duration.ofDays(14), "/api/v1/auth", "None"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secure=true");
    }
}
