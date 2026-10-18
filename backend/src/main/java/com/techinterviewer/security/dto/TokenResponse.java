package com.techinterviewer.security.dto;

import com.techinterviewer.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a successful authentication returns in the body: the access token and the account. The refresh token is
 * not part of it; it travels only in the {@code HttpOnly} cookie.
 */
@Schema(description = "A new access token and the account it belongs to.")
public record TokenResponse(
        @Schema(description = "JWT to send as `Authorization: Bearer <accessToken>`.") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Seconds until the access token expires.", example = "900") long expiresIn,
        UserResponse user) {

    public static final String BEARER = "Bearer";

    /** The access token never appears in a log line. */
    @Override
    public String toString() {
        return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", user=" + user + "]";
    }
}
