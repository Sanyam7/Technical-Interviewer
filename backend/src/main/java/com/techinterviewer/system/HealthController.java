package com.techinterviewer.system;

import com.techinterviewer.common.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE)
@Tag(name = "System")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Service health",
            description = "Public. Returns the same document with 200 when the database answers and 503 when it does not.")
    @ApiResponse(responseCode = "200", description = "Service and database are up",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = HealthResponse.class)))
    @ApiResponse(responseCode = "503", description = "The database does not answer",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = HealthResponse.class)))
    public ResponseEntity<HealthResponse> health() {
        HealthResponse health = healthService.check();
        HttpStatus status = health.status() == ComponentStatus.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }
}
