package com.techinterviewer.coding.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * When execution is on, checks after startup, in the background, that Docker answers and the sandbox images are present,
 * and pulls a missing image (runs themselves never pull: {@code --pull never}). It only logs: a missing image makes runs
 * fail as unavailable until it is there.
 */
public class SandboxWarmup {

    private static final Logger log = LoggerFactory.getLogger(SandboxWarmup.class);

    private final SandboxProperties properties;

    public SandboxWarmup(SandboxProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!properties.enabled()) {
            return;
        }
        Thread.ofVirtual().name("sandbox-warmup").start(this::check);
    }

    private void check() {
        if (docker(List.of("version", "--format", "{{.Server.Version}}"), 20) != 0) {
            log.warn("Code execution is on but Docker does not answer ('{} version' failed): runs will be refused", properties.dockerCommand());
            return;
        }
        for (String image : List.of(properties.images().java(), properties.images().node())) {
            if (docker(List.of("image", "inspect", image), 20) == 0) {
                log.info("Sandbox image {} is present", image);
            } else if (properties.pullOnStartup()) {
                log.info("Pulling sandbox image {} ...", image);
                int pulled = docker(List.of("pull", image), 600);
                log.info(pulled == 0 ? "Sandbox image {} pulled" : "Could not pull sandbox image {}: runs in its language will fail", image);
            } else {
                log.warn("Sandbox image {} is missing and app.execution.pull-on-startup is off: runs in its language will fail", image);
            }
        }
    }

    private int docker(List<String> arguments, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(properties.dockerCommand());
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException failure) {
            return -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
