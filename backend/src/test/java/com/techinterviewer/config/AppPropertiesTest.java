package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    void bindsACommaSeparatedListOfOrigins() {
        runner.withPropertyValues("app.cors.allowed-origins=http://localhost:5173, https://app.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppProperties.class).cors().allowedOrigins())
                            .isEqualTo(List.of("http://localhost:5173", "https://app.example.com"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "http://localhost:5173,*", "http://localhost:5173,,", " ", "localhost:5173",
        "https://app.example.com/", "${APP_CORS_ALLOWED_ORIGINS}"})
    void rejectsWildcardBlankAndMalformedOrigins(String origins) {
        runner.withPropertyValues("app.cors.allowed-origins=" + origins)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAMissingOriginList() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    private static final String ORIGINS = "app.cors.allowed-origins=http://localhost:5173";

    @Test
    void everythingExceptTheOriginsHasASafeDefault() {
        runner.withPropertyValues(ORIGINS).run(context -> {
            AppProperties properties = context.getBean(AppProperties.class);

            assertThat(properties.security().jwt().secret()).isNull();
            assertThat(properties.security().jwt().allowEphemeralSecret()).isFalse();
            assertThat(properties.security().jwt().issuer()).isEqualTo("tech-interviewer");
            assertThat(properties.security().jwt().accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.security().refreshCookie().name()).isEqualTo("refresh_token");
            assertThat(properties.security().refreshCookie().secure()).isTrue();
            assertThat(properties.security().refreshCookie().ttl()).isEqualTo(Duration.ofDays(14));
            assertThat(properties.security().refreshCookie().path()).isEqualTo("/api/v1/auth");
            assertThat(properties.rateLimit().authPerMinute()).isEqualTo(10);
            assertThat(properties.rateLimit().answersPerMinute()).isEqualTo(20);
            assertThat(properties.rateLimit().interviewsPerDay()).isEqualTo(10);
            assertThat(properties.interview().deadlineGrace()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.interview().maxFollowUpDepth()).isEqualTo(2);
        });
    }

    @Test
    void securityRateLimitAndInterviewSettingsCanBeOverridden() {
        runner.withPropertyValues(ORIGINS,
                "app.security.jwt.secret=abc", "app.security.jwt.issuer=other", "app.security.jwt.access-token-ttl=5m",
                "app.security.jwt.allow-ephemeral-secret=true",
                "app.security.refresh-cookie.name=rt", "app.security.refresh-cookie.secure=false",
                "app.security.refresh-cookie.ttl=7d", "app.security.refresh-cookie.path=/auth",
                "app.rate-limit.auth-per-minute=3", "app.rate-limit.answers-per-minute=4", "app.rate-limit.interviews-per-day=5",
                "app.interview.deadline-grace=45s", "app.interview.max-follow-up-depth=1")
                .run(context -> {
                    AppProperties properties = context.getBean(AppProperties.class);

                    assertThat(properties.security().jwt().secret()).isEqualTo("abc");
                    assertThat(properties.security().jwt().issuer()).isEqualTo("other");
                    assertThat(properties.security().jwt().accessTokenTtl()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.security().jwt().allowEphemeralSecret()).isTrue();
                    assertThat(properties.security().refreshCookie().name()).isEqualTo("rt");
                    assertThat(properties.security().refreshCookie().secure()).isFalse();
                    assertThat(properties.security().refreshCookie().ttl()).isEqualTo(Duration.ofDays(7));
                    assertThat(properties.security().refreshCookie().path()).isEqualTo("/auth");
                    assertThat(properties.rateLimit().authPerMinute()).isEqualTo(3);
                    assertThat(properties.rateLimit().answersPerMinute()).isEqualTo(4);
                    assertThat(properties.rateLimit().interviewsPerDay()).isEqualTo(5);
                    assertThat(properties.interview().deadlineGrace()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.interview().maxFollowUpDepth()).isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "app.security.jwt.issuer=",
        "app.security.jwt.access-token-ttl=0s",
        "app.security.jwt.access-token-ttl=30s",
        "app.security.jwt.access-token-ttl=25h",
        "app.security.refresh-cookie.name=",
        "app.security.refresh-cookie.name=bad name",
        "app.security.refresh-cookie.name=a;b",
        "app.security.refresh-cookie.ttl=10m",
        "app.security.refresh-cookie.ttl=91d",
        "app.security.refresh-cookie.path=",
        "app.security.refresh-cookie.path=relative",
        "app.security.refresh-cookie.path=/a;b",
        "app.rate-limit.auth-per-minute=0",
        "app.rate-limit.answers-per-minute=0",
        "app.rate-limit.interviews-per-day=-1",
        "app.rate-limit.auth-per-minute=10001",
        "app.interview.deadline-grace=-1s",
        "app.interview.deadline-grace=11m",
        "app.interview.max-follow-up-depth=3",
        "app.interview.max-follow-up-depth=-1"
    })
    void rejectsSettingsOutsideTheirSafeRange(String setting) {
        runner.withPropertyValues(ORIGINS, setting).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest(name = "grace {0} is accepted")
    @ValueSource(strings = {"0s", "1s", "30s", "10m"})
    void acceptsTheBoundariesOfTheGraceWindow(String grace) {
        runner.withPropertyValues(ORIGINS, "app.interview.deadline-grace=" + grace).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "ttl {0} is accepted")
    @ValueSource(strings = {"1m", "15m", "24h"})
    void acceptsTheBoundariesOfTheAccessTokenLifetime(String ttl) {
        runner.withPropertyValues(ORIGINS, "app.security.jwt.access-token-ttl=" + ttl).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theSecretIsNotPartOfTheStringFormOfTheProperties() {
        runner.withPropertyValues(ORIGINS, "app.security.jwt.secret=super-secret-value-123").run(context ->
                assertThat(context.getBean(AppProperties.class).toString()).doesNotContain("super-secret-value-123"));
    }
}
