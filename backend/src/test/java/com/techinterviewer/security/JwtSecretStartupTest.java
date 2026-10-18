package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TechInterviewerApplication;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.Role;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Starting without a signing secret: refused with an explanation outside the local profile, replaced by a
 * throw-away key (and a warning) in it. The tests give no JWT_SECRET themselves, so the test properties' secret
 * is explicitly blanked.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtSecretStartupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    @Import(JwtConfig.class)
    static class SecurityBeans {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /** Loads the real application.yml (and profile file) without the developer's environment variables. */
    private static ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + String.join(",", profiles), "app.security.jwt.secret=")
                .withUserConfiguration(SecurityBeans.class);
    }

    @Test
    void withoutASecretTheContextFailsToStartAndTheCauseIsTheMissingSecret() {
        runner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(MissingJwtSecretException.class);
            assertThat(context.getStartupFailure().getMessage()).doesNotContain("Unsatisfied");
        });
    }

    @Test
    void theProdProfileAlsoRefusesToStartWithoutASecret() {
        runner("prod").withPropertyValues("APP_CORS_ALLOWED_ORIGINS=https://app.example.com").run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(MissingJwtSecretException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tooshort", "AAAA", "not base64!!"})
    void anUnusableSecretIsRefusedEvenInTheLocalProfile(String secret) {
        runner("local").withPropertyValues("app.security.jwt.secret=" + secret).run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(MissingJwtSecretException.class));
    }

    @Test
    void theLocalProfileStartsWithAnEphemeralKeyAndWarns(CapturedOutput output) {
        runner("local").run(context -> {
            assertThat(context).hasNotFailed();
            JwtKeyProvider keys = context.getBean(JwtKeyProvider.class);
            assertThat(keys.isEphemeral()).isTrue();
            assertThat(keys.signingKey().getEncoded()).hasSize(48);
            assertThat(output.getAll()).contains("WARN").contains("JWT_SECRET is not set");
        });
    }

    @Test
    void theEphemeralKeyReallySignsAndVerifiesTokens() {
        runner("local").run(context -> {
            String token = context.getBean(JwtService.class).issueAccessToken(new AuthenticatedUser(UUID.randomUUID(), Role.ADMIN)).value();

            assertThat(context.getBean(JwtDecoder.class).decode(token).getClaimAsString("role")).isEqualTo("ADMIN");
        });
    }

    @Test
    void twoLocalStartsDoNotShareAKey() {
        byte[][] keys = new byte[2][];
        for (int i = 0; i < 2; i++) {
            int index = i;
            runner("local").run(context -> keys[index] = context.getBean(JwtKeyProvider.class).signingKey().getEncoded());
        }

        assertThat(keys[0]).isNotEqualTo(keys[1]);
    }

    @Test
    void aConfiguredSecretIsUsedEvenInTheLocalProfile() {
        runner("local").withPropertyValues("app.security.jwt.secret=" + com.techinterviewer.security.TestAuth.testSecret()).run(context -> {
            assertThat(context.getBean(JwtKeyProvider.class).isEphemeral()).isFalse();
            assertThat(context.getBean(JwtKeyProvider.class).signingKey().getEncoded())
                    .isEqualTo(java.util.Base64.getDecoder().decode(com.techinterviewer.security.TestAuth.testSecret()));
        });
    }

    /**
     * The whole application, against a database that is not there: it must stop on the secret before anything
     * tries to connect, and tell the operator what to do.
     */
    @Test
    void theWholeApplicationRefusesToStartAndExplainsHowToFixIt(CapturedOutput output) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(TechInterviewerApplication.class);

        assertThatThrownBy(() -> application.run(
                        "--app.security.jwt.secret=",
                        "--spring.datasource.password=irrelevant",
                        "--spring.datasource.url=jdbc:postgresql://localhost:1/unreachable",
                        "--server.port=0"))
                .rootCause()
                .isInstanceOf(MissingJwtSecretException.class);

        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .contains("JWT_SECRET")
                .contains("openssl rand -base64 48");
    }
}
