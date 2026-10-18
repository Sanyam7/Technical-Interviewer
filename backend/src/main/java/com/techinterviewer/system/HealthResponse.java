package com.techinterviewer.system;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Liveness and dependency status of the service.")
public record HealthResponse(
        @Schema(description = "UP only when every dependency is UP.", requiredMode = Schema.RequiredMode.REQUIRED) ComponentStatus status,
        @Schema(example = "tech-interviewer", requiredMode = Schema.RequiredMode.REQUIRED) String service,
        @Schema(example = "0.1.0-SNAPSHOT", requiredMode = Schema.RequiredMode.REQUIRED) String version,
        @Schema(description = "Authoritative server time (UTC).", requiredMode = Schema.RequiredMode.REQUIRED) Instant serverTime,
        @Schema(description = "Result of a trivial query against PostgreSQL.", requiredMode = Schema.RequiredMode.REQUIRED) ComponentStatus database) {
}
