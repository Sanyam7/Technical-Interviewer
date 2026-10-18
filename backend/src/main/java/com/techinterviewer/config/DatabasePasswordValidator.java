package com.techinterviewer.config;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * Stops the application at startup when the database password is missing. Without it, an unset
 * {@code POSTGRES_PASSWORD} reaches the driver as the literal text {@code ${POSTGRES_PASSWORD}} and the
 * failure is a misleading "password authentication failed".
 *
 * <p>It validates the effective {@link JdbcConnectionDetails}, so it applies whichever way they are
 * supplied: from {@code spring.datasource.*} or by a Testcontainers service connection.
 */
final class DatabasePasswordValidator implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JdbcConnectionDetails details) {
            requirePassword(details.getPassword());
        }
        return bean;
    }

    /** Rejects a null, blank or unresolved {@code ${...}} placeholder password. */
    static void requirePassword(@Nullable String password) {
        if (password == null || password.isBlank() || password.startsWith("${")) {
            throw new MissingDatabasePasswordException();
        }
    }
}
