package com.techinterviewer.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EvaluationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EvaluationProperties.class)
    static class Enabled {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Enabled.class);

    @Test
    void theDefaultsAreThreeAttemptsADoublingBackoffAndSeventyPercentCoverage() {
        runner.run(context -> {
            EvaluationProperties properties = context.getBean(EvaluationProperties.class);
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.backoffInitial()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.backoffMax()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.attemptLease()).isEqualTo(Duration.ofSeconds(90));
            assertThat(properties.minCoverage()).isEqualByComparingTo("0.70");
        });
    }

    @Test
    void theApplicationYmlRepeatsTheDefaultsOfTheRecord() throws Exception {
        var yaml = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);

        assertThat(yaml.getProperty("app.evaluation.max-attempts")).isEqualTo(3);
        assertThat(yaml.getProperty("app.evaluation.backoff-initial")).isEqualTo("2s");
        assertThat(yaml.getProperty("app.evaluation.backoff-max")).isEqualTo("10s");
        assertThat(yaml.getProperty("app.evaluation.attempt-lease")).isEqualTo("90s");
        assertThat(new BigDecimal(String.valueOf(yaml.getProperty("app.evaluation.min-coverage")))).isEqualByComparingTo("0.70");
    }

    @Test
    void theBackoffDoublesAndNeverExceedsTheCap() {
        EvaluationProperties properties = new EvaluationProperties(5, Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(90), new BigDecimal("0.70"));

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.backoffAfter(4)).as("16 s is capped").isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoffAfter(50)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aBackoffStartingAboveTheCapIsCapped() {
        EvaluationProperties properties = new EvaluationProperties(3, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(90), BigDecimal.ONE);

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.evaluation.max-attempts=0", "app.evaluation.max-attempts=11", "app.evaluation.min-coverage=-0.01",
        "app.evaluation.min-coverage=1.01", "app.evaluation.attempt-lease=0s", "app.evaluation.backoff-max=-1s", "app.evaluation.backoff-initial=6m"})
    void invalidValuesStopTheApplicationFromStarting(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theBoundsOfTheValidRangeAreAccepted() {
        runner.withPropertyValues("app.evaluation.max-attempts=1", "app.evaluation.min-coverage=0", "app.evaluation.backoff-initial=0s",
                "app.evaluation.backoff-max=0s").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EvaluationProperties.class).backoffAfter(1)).isEqualTo(Duration.ZERO);
                });
        runner.withPropertyValues("app.evaluation.max-attempts=10", "app.evaluation.min-coverage=1").run(context -> assertThat(context).hasNotFailed());
    }
}
