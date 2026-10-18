package com.techinterviewer.system;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final int DATABASE_QUERY_TIMEOUT_SECONDS = 2;
    /** Upper bound for the whole database check: pool wait, connect, query and a driver that stops answering. */
    private static final Duration DATABASE_PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final BuildProperties build;
    private final Clock clock;

    public HealthService(DataSource dataSource, BuildProperties build, Clock clock) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(DATABASE_QUERY_TIMEOUT_SECONDS);
        this.jdbc = JdbcClient.create(template);
        this.build = build;
        this.clock = clock;
    }

    public HealthResponse check() {
        ComponentStatus database = databaseStatus();
        return new HealthResponse(
                database,
                build.getArtifact(),
                build.getVersion(),
                Instant.now(clock),
                database);
    }

    /**
     * The query timeout and the driver's socket timeout only help while the driver cooperates, so the
     * check runs in its own virtual thread and the caller waits for it at most {@link #DATABASE_PROBE_TIMEOUT}.
     * A check that has not answered by then is cancelled (interrupting its thread, which closes the
     * socket it blocks on) and reported as DOWN.
     */
    private ComponentStatus databaseStatus() {
        FutureTask<ComponentStatus> probe = new FutureTask<>(() -> {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ComponentStatus.UP;
        });
        Thread.ofVirtual().name("health-database-probe").start(probe);
        try {
            return probe.get(DATABASE_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            probe.cancel(true);
            log.warn("Database health check did not answer within {} ms", DATABASE_PROBE_TIMEOUT.toMillis());
            return ComponentStatus.DOWN;
        } catch (InterruptedException ex) {
            probe.cancel(true);
            Thread.currentThread().interrupt();
            return ComponentStatus.DOWN;
        } catch (ExecutionException ex) {
            log.warn("Database health check failed: {}", NestedExceptionUtils.getMostSpecificCause(ex.getCause()).getMessage());
            return ComponentStatus.DOWN;
        }
    }
}
