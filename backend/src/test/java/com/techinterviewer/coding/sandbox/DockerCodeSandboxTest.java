package com.techinterviewer.coding.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The isolation of a container is a list of flags; this pins every one of them. */
class DockerCodeSandboxTest {

    private static final SandboxJob JOB = new SandboxJob("node:24-alpine", "echo hi", List.of(), "N1");

    @Test
    void runsEveryContainerIsolated() {
        List<String> command = new DockerCodeSandbox(Sandboxes.properties(true)).command("ti-sandbox-1", JOB);

        assertThat(String.join(" ", command)).contains(
                "run --rm --interactive",
                "--pull never",
                "--network none",
                "--read-only",
                "--tmpfs /tmp:rw,exec,nosuid,nodev,size=64m,mode=1777",
                "--memory 512m --memory-swap 512m",
                "--cpus 1",
                "--pids-limit 128",
                "--cap-drop ALL",
                "--security-opt no-new-privileges",
                "--user 65534:65534",
                "--ulimit nofile=256:256",
                "--ulimit fsize=16777216:16777216",
                "--log-driver none");
        assertThat(command).doesNotContain("--privileged", "-v", "--volume", "--mount");
        assertThat(command.subList(command.size() - 6, command.size())).containsExactly("node:24-alpine", "sh", "-c", "echo hi", "sh", "N1");
    }

    @Test
    void usesAStrongerRuntimeWhenOneIsConfigured() {
        SandboxProperties gvisor = new SandboxProperties(true, "docker", "runsc", 2, Duration.ofSeconds(1), Duration.ofSeconds(10), 256,
                BigDecimal.valueOf(0.5), 64, 65536, new SandboxProperties.Images("j", "n"), false);

        List<String> command = new DockerCodeSandbox(gvisor).command("ti-sandbox-1", JOB);

        assertThat(String.join(" ", command)).contains("--runtime runsc", "--memory 256m", "--cpus 0.5", "--pids-limit 64");
    }

    @Test
    void reportsAMissingDockerCommandAsUnavailable() {
        SandboxProperties broken = new SandboxProperties(true, "/no/such/docker", "", 1, Duration.ofSeconds(1), Duration.ofSeconds(5), 256,
                BigDecimal.ONE, 64, 65536, new SandboxProperties.Images("j", "n"), false);

        assertThatThrownBy(() -> new DockerCodeSandbox(broken).run(JOB))
                .isInstanceOf(SandboxUnavailableException.class)
                .hasMessageContaining("cannot be started");
    }

    @Test
    void writesAValidTarHeader() {
        byte[] header = TarArchive.header("input.json", 1234);
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : header[i] & 0xff;
        }

        assertThat(new String(header, 0, 10, StandardCharsets.US_ASCII)).isEqualTo("input.json");
        assertThat(Long.parseLong(new String(header, 124, 11, StandardCharsets.US_ASCII), 8)).isEqualTo(1234);
        assertThat(Long.parseLong(new String(header, 148, 6, StandardCharsets.US_ASCII), 8)).isEqualTo(sum);
        assertThat(new String(header, 257, 5, StandardCharsets.US_ASCII)).isEqualTo("ustar");
        assertThat(TarArchive.of(List.of(new SandboxJob.SandboxFile("a", new byte[600])))).hasSize(512 + 1024 + 1024);
    }

    @Test
    void refusesPathsAsFileNames() {
        assertThatThrownBy(() -> new SandboxJob.SandboxFile("../etc/passwd", new byte[0])).hasMessageContaining("plain name");
        assertThatThrownBy(() -> new SandboxJob.SandboxFile(".hidden", new byte[0])).hasMessageContaining("plain name");
    }
}
