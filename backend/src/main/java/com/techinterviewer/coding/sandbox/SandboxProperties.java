package com.techinterviewer.coding.sandbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where and how candidate code runs ({@code app.execution}; AI_ARCHITECTURE.md section 2.11). Off by default: without it,
 * coding answers are reviewed by the AI evaluator only and their correctness is capped as unverified.
 *
 * @param enabled         run tests at all
 * @param dockerCommand   the Docker CLI (a path when it is not on the PATH)
 * @param runtime         an OCI runtime for stronger isolation, for example {@code runsc} (gVisor); blank for Docker's default
 * @param maxConcurrent   containers running at the same time; further runs wait
 * @param queueTimeout    how long a run waits for a free slot before it is refused as busy
 * @param runTimeout      wall-clock limit of one container: start, compilation and every test
 * @param memoryMb        memory of a container (swap is not allowed on top)
 * @param cpus            CPU share of a container
 * @param pidsLimit       processes and threads of a container
 * @param maxOutputBytes  standard output kept from a container; more ends the run
 * @param images          the image per runtime; pulled at startup when {@code pullOnStartup}
 * @param pullOnStartup   pull missing images in the background when the application starts
 */
@ConfigurationProperties("app.execution")
@Validated
public record SandboxProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("docker") String dockerCommand,
        @NotNull @Pattern(regexp = "[a-z0-9._-]*") @DefaultValue("") String runtime,
        @Min(1) @Max(16) @DefaultValue("2") int maxConcurrent,
        @NotNull @DurationMin(millis = 0) @DurationMax(minutes = 2) @DefaultValue("15s") Duration queueTimeout,
        @NotNull @DurationMin(seconds = 5) @DurationMax(minutes = 2) @DefaultValue("30s") Duration runTimeout,
        @Min(128) @Max(4096) @DefaultValue("512") int memoryMb,
        @NotNull @DecimalMin("0.1") @DecimalMax("4.0") @DefaultValue("1.0") BigDecimal cpus,
        @Min(16) @Max(1024) @DefaultValue("128") int pidsLimit,
        @Min(4096) @Max(8_388_608) @DefaultValue("1048576") int maxOutputBytes,
        @Valid @NotNull @DefaultValue Images images,
        @DefaultValue("true") boolean pullOnStartup) {

    /** Sandbox images; pin them (tag or digest) so a run never pulls during an interview. */
    public record Images(
            @NotBlank @DefaultValue("eclipse-temurin:21-jdk-alpine") String java,
            @NotBlank @DefaultValue("node:24-alpine") String node) {
    }

    /** The longest a sandbox run can take, waiting for a slot included. */
    public Duration worstCase() {
        return queueTimeout.plus(runTimeout).plusSeconds(5);
    }
}
