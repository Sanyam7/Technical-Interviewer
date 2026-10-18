package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Loads the real application.yml and its profile files without starting the application, to pin the
 * values that no behaviour test would notice changing. The system environment is removed so that a
 * developer's exported variables cannot change the outcome.
 */
class ProfileConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfig {
    }

    private static ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + String.join(",", profiles))
                .withUserConfiguration(PropertiesConfig.class);
    }

    @Test
    void corsOriginsAreMandatoryInProd() {
        runner("prod").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodTakesItsCorsOriginsFromTheEnvironmentVariable() {
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppProperties.class).cors().allowedOrigins())
                    .containsExactly("https://app.example.com");
        });
    }

    @Test
    void prodRejectsAWildcardOrigin() {
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=*").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localDevelopmentDefaultsToTheViteOrigin() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppProperties.class).cors().allowedOrigins())
                    .isEqualTo(List.of("http://localhost:5173"));
        });
    }

    @Test
    void prodUsesEcsJsonLogsAndNoApiDocumentationEndpoints() {
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com").run(context -> {
            assertThat(context.getEnvironment().getProperty("logging.structured.format.console")).isEqualTo("ecs");
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        });
    }

    @Test
    void forwardedHeadersAreResolvedByTomcatOnlyInProd() {
        // "native" (Tomcat's RemoteIpValve) rewrites getRemoteAddr(), which the per-IP rate limits read; "framework" would not.
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com").run(context ->
                assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("native"));
        runner().run(context ->
                assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isNull());
        runner("local").run(context ->
                assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isNull());
    }

    @Test
    void apiDocumentationIsEnabledOnlyByTheLocalProfile() {
        runner("local").run(context -> {
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("true");
        });
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        });
    }

    @Test
    void errorResponsesNeverIncludeInternalDetails() {
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty("server.error.include-message")).isEqualTo("never");
            assertThat(context.getEnvironment().getProperty("server.error.include-binding-errors")).isEqualTo("never");
            assertThat(context.getEnvironment().getProperty("server.error.include-stacktrace")).isEqualTo("never");
            assertThat(context.getEnvironment().getProperty("server.error.include-exception")).isEqualTo("false");
        });
    }

    @Test
    void theDatabasePoolAndDriverAreBounded() {
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.data-source-properties.connectTimeout"))
                    .isEqualTo("5");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"))
                    .isEqualTo("60");
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.data-source-properties.cancelSignalTimeout"))
                    .isEqualTo("5");
        });
    }

    @Test
    void theApplicationYmlRepeatsTheDefaultsOfTheProperties() {
        // The yml values and the @DefaultValue of AppProperties are two copies of the same numbers: they must agree.
        AppProperties[] fromYml = new AppProperties[1];
        runner().run(context -> fromYml[0] = context.getBean(AppProperties.class));
        AppProperties[] fromDefaults = new AppProperties[1];
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("app.cors.allowed-origins=http://localhost:5173")
                .run(context -> fromDefaults[0] = context.getBean(AppProperties.class));

        assertThat(fromYml[0].security().jwt().issuer()).isEqualTo(fromDefaults[0].security().jwt().issuer());
        assertThat(fromYml[0].security().jwt().accessTokenTtl()).isEqualTo(fromDefaults[0].security().jwt().accessTokenTtl());
        assertThat(fromYml[0].security().jwt().allowEphemeralSecret()).isEqualTo(fromDefaults[0].security().jwt().allowEphemeralSecret());
        assertThat(fromYml[0].security().refreshCookie()).isEqualTo(fromDefaults[0].security().refreshCookie());
        assertThat(fromYml[0].rateLimit()).isEqualTo(fromDefaults[0].rateLimit());
        assertThat(fromYml[0].interview()).isEqualTo(fromDefaults[0].interview());
    }

    @Test
    void theRefreshCookieIsSecureEverywhereExceptTheLocalProfile() {
        runner().run(context -> assertThat(context.getBean(AppProperties.class).security().refreshCookie().secure()).isTrue());
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com").run(context ->
                assertThat(context.getBean(AppProperties.class).security().refreshCookie().secure()).isTrue());
        runner("local").run(context -> assertThat(context.getBean(AppProperties.class).security().refreshCookie().secure()).isFalse());
    }

    @Test
    void anEphemeralJwtSecretIsAllowedOnlyByTheLocalProfile() {
        runner().run(context -> assertThat(context.getBean(AppProperties.class).security().jwt().allowEphemeralSecret()).isFalse());
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com").run(context ->
                assertThat(context.getBean(AppProperties.class).security().jwt().allowEphemeralSecret()).isFalse());
        runner("local").run(context -> assertThat(context.getBean(AppProperties.class).security().jwt().allowEphemeralSecret()).isTrue());
    }

    @Test
    void theJwtSecretIsReadFromTheJwtSecretVariableAndHasNoDefaultInTheYml() throws Exception {
        // The test properties override the secret, so the yml is read on its own here.
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
        Object secret = sources.stream().map(source -> source.getProperty("app.security.jwt.secret"))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);

        assertThat(secret).isEqualTo("${JWT_SECRET:}");
    }

    @Test
    void tokenAndRefreshLifetimesCanBeSetThroughTheDocumentedEnvironmentVariables() {
        runner().withPropertyValues("JWT_ACCESS_TOKEN_TTL=5m", "JWT_REFRESH_TOKEN_TTL=7d").run(context -> {
            AppProperties properties = context.getBean(AppProperties.class);

            assertThat(properties.security().jwt().accessTokenTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
            assertThat(properties.security().refreshCookie().ttl()).isEqualTo(java.time.Duration.ofDays(7));
        });
    }

    @Test
    void aHostingPlatformCanSetThePortTheDatabaseUrlAndTheCookiesSameSiteThroughTheEnvironment() {
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com", "PORT=9090",
                "JDBC_DATABASE_URL=jdbc:postgresql://ep-quiet-sea.eu-central-1.aws.neon.tech/interviewer?sslmode=require",
                "APP_REFRESH_COOKIE_SAME_SITE=None").run(context -> {
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("9090");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://ep-quiet-sea.eu-central-1.aws.neon.tech/interviewer?sslmode=require");
            assertThat(context.getBean(AppProperties.class).security().refreshCookie().sameSite()).isEqualTo("None");
        });
        // An explicit SERVER_PORT wins over the platform's PORT; without either, 8080. Without a URL, host, port and database.
        runner().withPropertyValues("PORT=9090", "SERVER_PORT=8081").run(context ->
                assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8081"));
        runner().withPropertyValues("POSTGRES_HOST=db", "POSTGRES_DB=app").run(context -> {
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/app?sslmode=prefer");
            assertThat(context.getBean(AppProperties.class).security().refreshCookie().sameSite()).isEqualTo("Lax");
        });
    }

    @Test
    void sameSiteNoneOnAnInsecureCookieStopsTheStart() {
        runner("local").withPropertyValues("APP_REFRESH_COOKIE_SAME_SITE=None").run(context -> assertThat(context).hasFailed());
    }
}
