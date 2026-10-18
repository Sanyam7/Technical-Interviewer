package com.techinterviewer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application specific settings, bound from the {@code app.*} properties. Defaults live in the records
 * ({@link DefaultValue}) and are repeated in {@code application.yml} for visibility; a test keeps both equal.
 */
@ConfigurationProperties("app")
@Validated
public record AppProperties(
        @Valid @NotNull Cors cors,
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue RateLimit rateLimit,
        @Valid @NotNull @DefaultValue Interview interview,
        @Valid @NotNull @DefaultValue Limits limits) {

    /**
     * Browser origins allowed to call the API, e.g. {@code https://app.example.com}: scheme, host and
     * optional port, without a path or trailing slash. Credentials are allowed (refresh cookie), so
     * wildcard origins are rejected and every origin has to be listed explicitly. The pattern also
     * catches an unresolved {@code ${...}} placeholder.
     */
    public record Cors(
            @NotEmpty
            List<@NotBlank @Pattern(regexp = "https?://[^/\\s*]+",
                    message = "must be an http(s) origin without path or wildcard, e.g. https://app.example.com") String>
                    allowedOrigins) {
    }

    /** Token settings. */
    public record Security(
            @Valid @NotNull @DefaultValue Jwt jwt,
            @Valid @NotNull @DefaultValue RefreshCookie refreshCookie) {
    }

    /**
     * Access tokens.
     *
     * @param secret               base64 of the HS256 signing key ({@code JWT_SECRET}), at least 32 decoded bytes.
     *                             Optional here; the key provider refuses to start the application without one
     *                             unless {@code allowEphemeralSecret} is on
     * @param allowEphemeralSecret when no secret is configured, generate a random one at startup (tokens then die
     *                             with the process). Only the {@code local} profile turns it on
     * @param issuer               value of the {@code iss} claim; tokens from any other issuer are rejected
     * @param accessTokenTtl       lifetime of an access token
     */
    public record Jwt(
            @Nullable String secret,
            @DefaultValue("false") boolean allowEphemeralSecret,
            @NotBlank @DefaultValue("tech-interviewer") String issuer,
            @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 24) @DefaultValue("15m") Duration accessTokenTtl) {

        /** The secret never appears in logs or in the actuator. */
        @Override
        public String toString() {
            return "Jwt[secret=" + (secret == null || secret.isBlank() ? "<unset>" : "<redacted>")
                    + ", allowEphemeralSecret=" + allowEphemeralSecret + ", issuer=" + issuer
                    + ", accessTokenTtl=" + accessTokenTtl + "]";
        }
    }

    /**
     * The cookie that carries the opaque refresh token.
     *
     * @param name   cookie name
     * @param secure the {@code Secure} attribute; true except on a developer's machine over plain http
     * @param ttl    lifetime of the cookie and of the refresh token
     * @param path   {@code Path} attribute; narrow, so the cookie is only sent to the auth endpoints
     */
    public record RefreshCookie(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "must be a valid cookie name") @DefaultValue("refresh_token") String name,
            @DefaultValue("true") boolean secure,
            @NotNull @DurationMin(hours = 1) @DurationMax(days = 90) @DefaultValue("14d") Duration ttl,
            @NotBlank @Pattern(regexp = "/[^\\s;,]*", message = "must be an absolute path without spaces") @DefaultValue("/api/v1/auth") String path,
            @NotNull @Pattern(regexp = "Lax|Strict|None", message = "must be Lax, Strict or None") @DefaultValue("Lax") String sameSite) {

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public RefreshCookie {
            // Browsers drop a SameSite=None cookie that is not Secure, so the combination would silently break sign-in.
            if ("None".equals(sameSite) && !secure) {
                throw new IllegalArgumentException("app.security.refresh-cookie.same-site=None requires secure=true");
            }
        }

        /** The default SameSite=Lax: the app and the API share a site. */
        public RefreshCookie(String name, boolean secure, Duration ttl, String path) {
            this(name, secure, ttl, path, "Lax");
        }
    }

    /**
     * Rate limits and quotas (Bucket4j buckets in memory, so per instance).
     *
     * @param authPerMinute    register, login and refresh requests per client IP per minute
     * @param answersPerMinute answer submissions per user per minute
     * @param interviewsPerDay interviews a user may create per UTC day (counted from the database)
     * @param writesPerMinute  every other state-changing request (POST, PUT, DELETE) per user per minute, so that
     *                         endpoints that will call an AI provider cannot be hammered
     * @param codeRunsPerMinute "run examples" requests per user per minute (each starts a sandbox container)
     * @param resumeUploadsPerHour résumé uploads per user per hour (each is a model call over a whole résumé)
     * @param loginFailuresPerAccount failed sign-ins for one e-mail address within {@code loginFailureWindow} before further
     *                         attempts for that address are refused, whatever the password; counted for unknown addresses too
     * @param loginFailureWindow how long failed sign-ins of one address are remembered
     */
    public record RateLimit(
            @Min(1) @Max(10_000) @DefaultValue("10") int authPerMinute,
            @Min(1) @Max(10_000) @DefaultValue("20") int answersPerMinute,
            @Min(1) @Max(10_000) @DefaultValue("10") int interviewsPerDay,
            @Min(1) @Max(10_000) @DefaultValue("60") int writesPerMinute,
            @Min(1) @Max(10_000) @DefaultValue("10") int codeRunsPerMinute,
            @Min(1) @Max(10_000) @DefaultValue("10") int resumeUploadsPerHour,
            @Min(3) @Max(1_000) @DefaultValue("10") int loginFailuresPerAccount,
            @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 24) @DefaultValue("15m") Duration loginFailureWindow) {
    }

    /**
     * Request size limits, enforced before a body is read.
     *
     * @param maxBodyBytes     largest accepted request body of any endpoint (an answer is at most 20,000 characters)
     * @param authMaxBodyBytes largest accepted request body of the public {@code /api/v1/auth/*} endpoints
     * @param resumeMaxBodyBytes largest accepted request body of the résumé upload (a 2 MiB file and its multipart framing)
     */
    public record Limits(
            @Min(1_024) @Max(10_485_760) @DefaultValue("262144") int maxBodyBytes,
            @Min(256) @Max(1_048_576) @DefaultValue("16384") int authMaxBodyBytes,
            @Min(1_024) @Max(10_485_760) @DefaultValue("2162688") int resumeMaxBodyBytes) {
    }

    /**
     * Interview behaviour.
     *
     * @param deadlineGrace    how long after {@code deadline_at} an answer is still accepted
     * @param maxFollowUpDepth follow-ups allowed below one root question (the schema allows 0 to 2)
     * @param maxDesignProbes  dimension probes allowed after a system design problem (0 to 6)
     */
    public record Interview(
            @NotNull @DurationMin(seconds = 0) @DurationMax(minutes = 10) @DefaultValue("30s") Duration deadlineGrace,
            @Min(0) @Max(2) @DefaultValue("2") int maxFollowUpDepth,
            @Min(0) @Max(6) @DefaultValue("4") int maxDesignProbes) {
    }
}
