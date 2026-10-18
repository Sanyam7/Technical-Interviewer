package com.techinterviewer.profile;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.profile.dto.ProfileResponse;
import com.techinterviewer.profile.dto.UpdateProfileRequest;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated candidate's own profile. The user comes from the access token; no id is accepted from the client. */
@RestController
@RequestMapping(ApiPaths.BASE + "/profile")
@Tag(name = "Profile")
public class ProfileController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping(produces = JSON)
    @Operation(
            operationId = "getProfile",
            summary = "My profile",
            description = "The profile of the authenticated user. It is created empty on first access.")
    @ApiResponse(responseCode = "200", description = "The profile",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ProfileResponse.class)))
    public ProfileResponse get(@CurrentUser AuthenticatedUser user) {
        return profiles.get(user.id());
    }

    @PutMapping(consumes = JSON, produces = JSON)
    @Operation(
            operationId = "replaceProfile",
            summary = "Replace my profile",
            description = "Replaces headline, years of experience, job title and the whole set of self-rated skills. "
                    + "Absent or blank text fields are cleared; skills not listed are removed.")
    @ApiResponse(responseCode = "200", description = "The stored profile",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ProfileResponse.class)))
    @ApiResponse(responseCode = "400", description = "A field is invalid, a skill is unknown or inactive, or a skill is listed twice (code VALIDATION_FAILED, field errors in errors)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    public ProfileResponse replace(@CurrentUser AuthenticatedUser user, @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.replace(user.id(), request);
    }
}
