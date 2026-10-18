package com.techinterviewer.security.dto;

import com.techinterviewer.security.PasswordPolicy;
import com.techinterviewer.user.EmailAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Login credentials. The email is trimmed and lower-cased like at registration. It is deliberately not checked
 * for shape: whatever is wrong with the credentials, the answer is the same generic 401. A password longer than
 * BCrypt reads can never be correct, so it is rejected up front.
 */
@Schema(description = "Credentials of an existing account.")
public record LoginRequest(
        @Schema(example = "ada@example.com")
        @NotBlank @Size(max = EmailAddress.MAX_LENGTH)
        String email,

        @Schema(example = "correct-horse-battery", format = "password")
        @NotNull @PasswordBytes(min = 1, max = PasswordPolicy.MAX_BYTES)
        String password) {

    public LoginRequest {
        email = email == null ? null : EmailAddress.normalize(email);
        password = password == null ? null : PasswordPolicy.normalize(password);
    }

    /** The password never appears in a log line. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<redacted>]";
    }
}
