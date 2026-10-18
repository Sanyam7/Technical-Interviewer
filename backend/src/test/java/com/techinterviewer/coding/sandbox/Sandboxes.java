package com.techinterviewer.coding.sandbox;

import java.math.BigDecimal;
import java.time.Duration;

/** Sandbox settings for tests. */
public final class Sandboxes {

    private Sandboxes() {
    }

    public static SandboxProperties properties(boolean enabled) {
        return properties(enabled, Duration.ofSeconds(30), 1_048_576);
    }

    public static SandboxProperties properties(boolean enabled, Duration runTimeout, int maxOutputBytes) {
        return new SandboxProperties(enabled, System.getenv().getOrDefault("DOCKER_COMMAND", "docker"), "", 2, Duration.ofSeconds(60),
                runTimeout, 512, BigDecimal.ONE, 128, maxOutputBytes,
                new SandboxProperties.Images("eclipse-temurin:21-jdk-alpine", "node:24-alpine"), true);
    }
}
