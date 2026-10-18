package com.techinterviewer.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final HealthService service =
            new HealthService(new UnreachableDataSource(), buildProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reportsDownWhenTheDatabaseCannotBeReached() {
        HealthResponse response = service.check();

        assertThat(response).isEqualTo(
                new HealthResponse(ComponentStatus.DOWN, "tech-interviewer", "1.2.3", NOW, ComponentStatus.DOWN));
    }

    @Test
    void controllerAnswers503WithTheSameDocumentShape() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(service)).build();

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"))
                .andExpect(jsonPath("$.service").value("tech-interviewer"))
                .andExpect(jsonPath("$.version").value("1.2.3"))
                .andExpect(jsonPath("$.serverTime").value("2026-10-05T12:00:00Z"));
    }

    @ParameterizedTest(name = "database hangs on {0}")
    @EnumSource(HangingDataSource.HangsOn.class)
    void aHangingDatabaseIsReportedDownWithinAboutTwoSecondsAndTheWaitingThreadIsReleased(HangingDataSource.HangsOn hangsOn)
            throws InterruptedException {
        HangingDataSource dataSource = new HangingDataSource(hangsOn);
        HealthService hanging = new HealthService(dataSource, buildProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        long start = System.nanoTime();
        HealthResponse response = hanging.check();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(response.database()).isEqualTo(ComponentStatus.DOWN);
        assertThat(elapsed).isBetween(Duration.ofMillis(1800), Duration.ofSeconds(3));
        assertThat(dataSource.blockedThreadInterrupted.await(2, TimeUnit.SECONDS))
                .as("the probe thread blocked in the driver was cancelled")
                .isTrue();
    }

    private static BuildProperties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty("group", "com.techinterviewer");
        properties.setProperty("artifact", "tech-interviewer");
        properties.setProperty("version", "1.2.3");
        return new BuildProperties(properties);
    }

    /** A data source whose connection (or first statement) never answers, like a frozen database server. */
    private static final class HangingDataSource extends AbstractDataSource {

        enum HangsOn {
            CONNECT,
            STATEMENT
        }

        private final HangsOn hangsOn;
        private final CountDownLatch neverReleased = new CountDownLatch(1);
        final CountDownLatch blockedThreadInterrupted = new CountDownLatch(1);

        HangingDataSource(HangsOn hangsOn) {
            this.hangsOn = hangsOn;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (hangsOn == HangsOn.CONNECT) {
                block();
            }
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "close":
                                return null;
                            case "isClosed":
                                return false;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return "HangingConnection";
                            default:
                                block();
                                return null;
                        }
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private void block() throws SQLException {
            try {
                neverReleased.await();
            } catch (InterruptedException ex) {
                blockedThreadInterrupted.countDown();
                throw new SQLException("interrupted while blocked", ex);
            }
        }
    }

    private static final class UnreachableDataSource extends AbstractDataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("connection refused");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("connection refused");
        }
    }
}
