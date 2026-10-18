package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TechInterviewerApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Starts the whole application against an unreachable database URL: it has to fail on the password
 * before anything tries to connect. Command line arguments are used because they win over application.yml.
 */
class DatabasePasswordStartupTest {

    @ParameterizedTest(name = "spring.datasource.password=\"{0}\"")
    @ValueSource(strings = {"", "${POSTGRES_PASSWORD_THAT_IS_NOT_DEFINED_ANYWHERE}"})
    void applicationRefusesToStartWithoutADatabasePassword(String password) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(TechInterviewerApplication.class);

        assertThatThrownBy(() -> application.run(
                        "--spring.datasource.password=" + password,
                        "--spring.datasource.url=jdbc:postgresql://localhost:1/unreachable",
                        "--server.port=0"))
                .rootCause()
                .isInstanceOf(MissingDatabasePasswordException.class)
                .hasMessage(new MissingDatabasePasswordException().getMessage());
    }
}
