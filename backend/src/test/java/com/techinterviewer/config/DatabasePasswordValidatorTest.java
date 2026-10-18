package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

class DatabasePasswordValidatorTest {

    private final DatabasePasswordValidator validator = new DatabasePasswordValidator();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "${POSTGRES_PASSWORD}", "${SOMETHING_ELSE:}"})
    void missingOrUnresolvedPasswordsAreRejectedWithAMessageNamingTheCause(String password) {
        assertThatThrownBy(() -> DatabasePasswordValidator.requirePassword(password))
                .isInstanceOf(MissingDatabasePasswordException.class)
                .hasMessage("POSTGRES_PASSWORD is not set. Copy .env.example to .env in the repository root"
                        + " and run the backend from backend/.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"change-me", "p$ss{word}", "a ${b}", "x"})
    void anyRealPasswordIsAccepted(String password) {
        assertThatCode(() -> DatabasePasswordValidator.requirePassword(password)).doesNotThrowAnyException();
    }

    @Test
    void theEffectiveConnectionDetailsAreValidated() {
        assertThatThrownBy(() -> validator.postProcessAfterInitialization(details("${POSTGRES_PASSWORD}"), "details"))
                .isInstanceOf(MissingDatabasePasswordException.class)
                .hasMessageStartingWith("POSTGRES_PASSWORD is not set");
        JdbcConnectionDetails valid = details("secret");

        assertThat(validator.postProcessAfterInitialization(valid, "details")).isSameAs(valid);
    }

    @Test
    void failureAnalyzerExplainsTheCauseAndTheFix() {
        FailureAnalysis analysis = new MissingDatabasePasswordFailureAnalyzer()
                .analyze(new RuntimeException("bean creation failed", new MissingDatabasePasswordException()));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("POSTGRES_PASSWORD");
        assertThat(analysis.getAction()).isEqualTo(
                "Copy .env.example to .env in the repository root and run the backend from backend/.");
    }

    @Test
    void otherBeansAreLeftAlone() {
        Object bean = new Object();

        assertThat(validator.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
    }

    private static JdbcConnectionDetails details(String password) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return "jdbc:postgresql://localhost/db";
            }

            @Override
            public String getUsername() {
                return "user";
            }

            @Override
            public String getPassword() {
                return password;
            }
        };
    }
}
