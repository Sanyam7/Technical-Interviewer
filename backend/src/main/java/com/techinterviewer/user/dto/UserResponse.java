package com.techinterviewer.user.dto;

import com.techinterviewer.user.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The public view of an account. Never carries the password hash. */
@Schema(description = "An account.")
public record UserResponse(
        @Schema(example = "0192f5c2-8a3e-7c1d-9b1e-3f4a5b6c7d8e") UUID id,
        @Schema(example = "ada@example.com") String email,
        @Schema(example = "Ada") String displayName,
        Role role) {
}
