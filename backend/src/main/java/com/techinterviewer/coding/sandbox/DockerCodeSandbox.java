package com.techinterviewer.coding.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs each job in a new Docker container that is removed afterwards. The container gets no network, a read-only root
 * file system with a small writable {@code /tmp}, bounded memory (no swap), CPU, processes, open files and file size, no
 * Linux capabilities, no privilege escalation and an unprivileged user; it never pulls an image and keeps no logs. The
 * sources arrive on standard input as a tar stream. A run that exceeds its wall-clock limit or its output limit is killed.
 *
 * <p>Limitation: with Docker's default runtime the container shares the host kernel, so a kernel exploit escapes it.
 * {@code app.execution.runtime=runsc} (gVisor) closes that gap where it is installed; a public deployment should run the
 * sandbox on hosts or VMs of its own (AI_ARCHITECTURE.md section 2.11).
 */
public final class DockerCodeSandbox implements CodeSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeSandbox.class);
    private static final int MAX_STDERR_BYTES = 8 * 1024;
    private static final int DOCKER_FAILURE = 125;

    private final SandboxProperties properties;
    private final Semaphore slots;

    public DockerCodeSandbox(SandboxProperties properties) {
        this.properties = properties;
        this.slots = new Semaphore(properties.maxConcurrent(), true);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public SandboxResult run(SandboxJob job) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(properties.queueTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SandboxUnavailableException("Interrupted while waiting for the sandbox.", interrupted);
        }
        if (!acquired) {
            throw new SandboxUnavailableException("Every sandbox is busy. Try again in a moment.", true);
        }
        try {
            return execute(job);
        } finally {
            slots.release();
        }
    }

    /** The {@code docker run} command line; public for the test that pins every isolation flag. */
    public List<String> command(String containerName, SandboxJob job) {
        List<String> command = new ArrayList<>(List.of(
                properties.dockerCommand(), "run", "--rm", "--interactive",
                "--name", containerName,
                "--label", "app=tech-interviewer-sandbox",
                "--pull", "never",
                "--network", "none",
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=64m,mode=1777",
                "--memory", properties.memoryMb() + "m",
                "--memory-swap", properties.memoryMb() + "m",
                "--cpus", properties.cpus().toPlainString(),
                "--pids-limit", Integer.toString(properties.pidsLimit()),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "65534:65534",
                "--ulimit", "nofile=256:256",
                "--ulimit", "fsize=16777216:16777216",
                "--log-driver", "none",
                "--workdir", "/tmp",
                "--env", "HOME=/tmp"));
        if (!properties.runtime().isBlank()) {
            command.addAll(List.of("--runtime", properties.runtime()));
        }
        command.addAll(List.of(job.image(), "sh", "-c", job.script(), "sh", job.nonce()));
        return command;
    }

    private SandboxResult execute(SandboxJob job) {
        String name = "ti-sandbox-" + UUID.randomUUID();
        long start = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command(name, job)).start();
        } catch (IOException missing) {
            throw new SandboxUnavailableException("The Docker command '" + properties.dockerCommand() + "' cannot be started.", missing);
        }
        CappedReader stdout = new CappedReader(process.getInputStream(), properties.maxOutputBytes());
        CappedReader stderr = new CappedReader(process.getErrorStream(), MAX_STDERR_BYTES);
        Thread.ofVirtual().name("sandbox-stdout").start(stdout);
        Thread.ofVirtual().name("sandbox-stderr").start(stderr);
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(TarArchive.of(job.files()));
        } catch (IOException closed) {
            log.debug("The sandbox closed its input early", closed);
        }
        boolean timedOut = false;
        boolean truncated = false;
        try {
            long deadline = System.nanoTime() + properties.runTimeout().toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (stdout.full()) {
                    truncated = true;
                    break;
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    break;
                }
            }
            if (timedOut || truncated) {
                kill(name);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            stdout.awaitEnd(Duration.ofSeconds(2));
            stderr.awaitEnd(Duration.ofSeconds(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            kill(name);
            process.destroyForcibly();
            throw new SandboxUnavailableException("Interrupted while the sandbox ran.", interrupted);
        }
        // The output can fill up in the last moments of a run, after the loop above saw the process end.
        truncated = truncated || stdout.full();
        int exit = process.isAlive() ? -1 : process.exitValue();
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        String errors = stderr.text();
        if (exit == DOCKER_FAILURE && !timedOut && !truncated) {
            log.warn("The sandbox could not start a container: {}", errors.strip());
            throw new SandboxUnavailableException("The sandbox could not start: " + firstLine(errors), false);
        }
        return new SandboxResult(timedOut || truncated ? -1 : exit, stdout.text(), errors, timedOut, truncated, durationMs);
    }

    private void kill(String name) {
        try {
            Process killer = new ProcessBuilder(properties.dockerCommand(), "kill", name).redirectErrorStream(true).start();
            killer.getInputStream().transferTo(OutputStream.nullOutputStream());
            killer.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not kill sandbox container {}; it is removed when it ends (--rm)", name, failure);
        }
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("unknown error");
        return line.length() <= 200 ? line : line.substring(0, 200);
    }

    /** Reads a stream to its end, keeping at most {@code limit} bytes. */
    private static final class CappedReader implements Runnable {

        private final InputStream in;
        private final int limit;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final Object lock = new Object();
        private volatile boolean full;
        private volatile boolean ended;

        CappedReader(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (lock) {
                        int room = limit - kept.size();
                        kept.write(buffer, 0, Math.min(room, read));
                        if (read > room) {
                            full = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process was destroyed
            } finally {
                ended = true;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        }

        boolean full() {
            return full;
        }

        void awaitEnd(Duration timeout) throws InterruptedException {
            long until = System.nanoTime() + timeout.toNanos();
            synchronized (lock) {
                while (!ended && System.nanoTime() < until) {
                    lock.wait(50);
                }
            }
        }

        String text() {
            synchronized (lock) {
                return kept.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
