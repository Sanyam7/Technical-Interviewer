package com.techinterviewer.interview.api;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.common.api.PageResponse;
import com.techinterviewer.interview.api.dto.ConfigurationRequest;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.service.InterviewConfigurationService;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's saved interview configurations. The user comes from the access token; a configuration of somebody else
 * answers 404 like a missing one.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/interview-configurations")
@Tag(name = "Interview configurations")
public class InterviewConfigurationController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    private final InterviewConfigurationService configurations;

    public InterviewConfigurationController(InterviewConfigurationService configurations) {
        this.configurations = configurations;
    }

    @PostMapping(consumes = JSON, produces = JSON)
    @Operation(
            operationId = "createInterviewConfiguration",
            summary = "Save an interview configuration",
            description = "Validates the configuration and saves it for the caller. A configuration never changes afterwards.")
    @ApiResponse(responseCode = "201", description = "The saved configuration",
            headers = @Header(name = "Location", description = "URL of the new configuration"),
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigurationResponse.class)))
    @ApiResponse(responseCode = "400", description = "A field is invalid, a skill is unknown, inactive or repeated, or the coding language does not fit the interview type (code VALIDATION_FAILED, field errors in errors)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public ResponseEntity<ConfigurationResponse> create(
            @CurrentUser AuthenticatedUser user, @Valid @RequestBody ConfigurationRequest request) {
        ConfigurationResponse created = configurations.create(user.id(), request);
        return ResponseEntity.created(URI.create(ApiPaths.BASE + "/interview-configurations/" + created.id())).body(created);
    }

    @GetMapping(produces = JSON)
    @Operation(
            operationId = "listInterviewConfigurations",
            summary = "My configurations",
            description = "The caller's configurations, newest first.")
    @ApiResponse(responseCode = "200", description = "One page of configurations")
    @ApiResponse(responseCode = "400", description = "page or size is out of range (code VALIDATION_FAILED)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public PageResponse<ConfigurationResponse> list(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return configurations.list(user.id(), page, size);
    }

    @GetMapping(path = "/{id}", produces = JSON)
    @Operation(operationId = "getInterviewConfiguration", summary = "One configuration", description = "A configuration of the caller.")
    @ApiResponse(responseCode = "200", description = "The configuration",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ConfigurationResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "No such configuration of the caller (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public ConfigurationResponse get(@CurrentUser AuthenticatedUser user, @Parameter(description = "Configuration id") @PathVariable UUID id) {
        return configurations.get(user.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "deleteInterviewConfiguration",
            summary = "Delete a configuration",
            description = "Deletes a configuration of the caller that no interview was created from.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "No such configuration of the caller (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "An interview uses the configuration (code CONFIGURATION_IN_USE)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public void delete(@CurrentUser AuthenticatedUser user, @Parameter(description = "Configuration id") @PathVariable UUID id) {
        configurations.delete(user.id(), id);
    }
}
