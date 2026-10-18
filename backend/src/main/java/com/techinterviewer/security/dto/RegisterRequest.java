package com.techinterviewer.security.dto;

import com.techinterviewer.common.validation.SingleLineText;
import com.techinterviewer.security.PasswordPolicy;
import com.techinterviewer.user.EmailAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service registration. The role is not a field: an account created here is always a candidate (an unknown
 * property is rejected). The email and the display name are trimmed and the email is lower-cased when the request
 * is built, so every later step sees the canonical values.
 */
@Schema(description = "Registration of a candidate account.")
public record RegisterRequest(
        @Schema(example = "ada@example.com", description = "Unique, case-insensitive. ASCII addresses only, at most 254 characters.")
        @NotBlank @Size(max = EmailAddress.MAX_LENGTH)
        @Pattern(regexp = EmailAddress.REGEX, message = "must be a valid email address")
        String email,

        @Schema(example = "correct-horse-battery", description = "10 to 72 bytes when encoded as UTF-8.", format = "password")
        @NotNull @PasswordBytes(min = PasswordPolicy.MIN_BYTES, max = PasswordPolicy.MAX_BYTES)
        String password,

        @Schema(example = "Ada", description = "1 to 80 characters, no control characters.")
        @NotBlank @Size(max = 80) @SingleLineText
        String displayName) {

    public RegisterRequest {
        email = email == null ? null : EmailAddress.normalize(email);
        password = password == null ? null : PasswordPolicy.normalize(password);
        displayName = displayName == null ? null : displayName.strip();
    }

    /** The password never appears in a log line. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=<redacted>, displayName=" + displayName + "]";
    }
}
