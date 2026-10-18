package com.techinterviewer.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    /** Static, so the post-processor is registered before any other bean (including the connection details) is created. */
    @Bean
    static BeanPostProcessor databasePasswordValidator() {
        return new DatabasePasswordValidator();
    }

    /**
     * Two roles, when {@code POSTGRES_APP_USER} is set: the application connects as that role (see {@code spring.datasource}
     * in application.yml), which only reads and writes rows, while Flyway migrates as the owner ({@code POSTGRES_USER}). Without
     * it, one role does both, as on a developer's machine. Backs SECURITY.md, DB-1.
     */
    @Bean
    FlywayConfigurationCustomizer migrateAsTheOwnerWhenTheApplicationHasItsOwnRole(Environment environment, DataSource runtime) {
        String applicationRole = environment.getProperty("POSTGRES_APP_USER", "");
        if (applicationRole.isBlank()) {
            return configuration -> { };
        }
        String owner = environment.getProperty("POSTGRES_USER", "interviewer");
        String ownerPassword = environment.getProperty("POSTGRES_PASSWORD");
        DatabasePasswordValidator.requirePassword(ownerPassword);
        String url = runtime instanceof HikariDataSource hikari ? hikari.getJdbcUrl() : environment.getProperty("spring.datasource.url");
        return configuration -> configuration.dataSource(url, owner, ownerPassword);
    }

    /** Switched off only by tests whose throw-away database is owned by a superuser ({@code app.database.role-check=false}). */
    @Bean
    @Profile("prod")
    @ConditionalOnProperty(name = "app.database.role-check", havingValue = "true", matchIfMissing = true)
    ApplicationRunner runtimeRoleCheck(DataSource dataSource) {
        return new RuntimeRoleCheck(dataSource);
    }
}
