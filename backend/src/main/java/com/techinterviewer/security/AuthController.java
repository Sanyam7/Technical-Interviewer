package com.techinterviewer.security;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.security.dto.LoginRequest;
import com.techinterviewer.security.dto.RegisterRequest;
import com.techinterviewer.security.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. The access token travels in the response body (the client keeps it in memory); the
 * refresh token only in an {@code HttpOnly} cookie, which the {@code refresh} and {@code logout} endpoints read.
 * Because browsers attach that cookie to cross-site requests, those two endpoints also demand the custom header
 * {@code X-Requested-With: XMLHttpRequest}, which a cross-site form or image request cannot add and a cross-origin
 * script can only add after a CORS preflight that the allow-list refuses.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/auth")
@Tag(name = "Authentication")
class AuthController {

    static final String REQUESTED_WITH = "X-Requested-With";
    static final String REQUESTED_WITH_VALUE = "XMLHttpRequest";
    static final String MISSING_REQUESTED_WITH_DETAIL =
            "This endpoint requires the header " + REQUESTED_WITH + ": " + REQUESTED_WITH_VALUE + ".";

    private static final String SET_COOKIE_DESCRIPTION =
            "The refresh token: HttpOnly, SameSite=Lax (configurable), limited to /api/v1/auth, Secure unless configured otherwise.";

    private final AuthService auth;
    private final RefreshCookieFactory cookies;

    AuthController(AuthService auth, RefreshCookieFactory cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a candidate account and sign in",
            description = "The account is always a CANDIDATE. Returns an access token and sets the refresh cookie.")
    @ApiResponse(responseCode = "201", description = "Account created.",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = SET_COOKIE_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Invalid email, password or display name (code VALIDATION_FAILED).")
    @ApiResponse(responseCode = "409", description = "The email address is already registered (code EMAIL_TAKEN).")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return withRefreshCookie(HttpStatus.CREATED, auth.register(request));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Sign in with email and password",
            description = "Every failure answers the same 401 INVALID_CREDENTIALS, whether the address is unknown, "
                    + "the password wrong or the account disabled.")
    @ApiResponse(responseCode = "200", description = "Signed in.",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = SET_COOKIE_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = "Invalid credentials (code INVALID_CREDENTIALS).")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(HttpStatus.OK, auth.login(request));
    }

    @PostMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Exchange the refresh cookie for a new access token",
            description = "Rotates the refresh token: the cookie is replaced. Presenting an already used token revokes "
                    + "the whole session family. Requires the header X-Requested-With: XMLHttpRequest.")
    @Parameter(name = REQUESTED_WITH, in = ParameterIn.HEADER, required = true,
            schema = @Schema(allowableValues = REQUESTED_WITH_VALUE))
    @ApiResponse(responseCode = "200", description = "A new access token; the refresh cookie was rotated.",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = SET_COOKIE_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401",
            description = "No cookie, or the token is unknown, expired, used or revoked (code UNAUTHENTICATED). The cookie is cleared.")
    @ApiResponse(responseCode = "403", description = "The X-Requested-With header is missing (code FORBIDDEN).")
    ResponseEntity<TokenResponse> refresh(
            HttpServletRequest request, @RequestHeader(name = REQUESTED_WITH, required = false) @Nullable String requestedWith) {
        requireRequestedWith(requestedWith);
        try {
            return withRefreshCookie(HttpStatus.OK, auth.refresh(cookies.read(request)));
        } catch (ApiException rejected) {
            throw rejected.withHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out",
            description = "Revokes the session family of the refresh cookie and clears the cookie. Idempotent: it also "
                    + "answers 204 without a cookie. Requires the header X-Requested-With: XMLHttpRequest.")
    @Parameter(name = REQUESTED_WITH, in = ParameterIn.HEADER, required = true,
            schema = @Schema(allowableValues = REQUESTED_WITH_VALUE))
    @ApiResponse(responseCode = "204", description = "Signed out.", content = @Content)
    @ApiResponse(responseCode = "403", description = "The X-Requested-With header is missing (code FORBIDDEN).")
    ResponseEntity<Void> logout(
            HttpServletRequest request, @RequestHeader(name = REQUESTED_WITH, required = false) @Nullable String requestedWith) {
        requireRequestedWith(requestedWith);
        auth.logout(cookies.read(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(HttpStatus status, AuthSession session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken()).toString())
                .body(session.body());
    }

    private static void requireRequestedWith(@Nullable String value) {
        if (!REQUESTED_WITH_VALUE.equals(value)) {
            throw new ApiException(ErrorCode.FORBIDDEN, MISSING_REQUESTED_WITH_DETAIL);
        }
    }
}
