package com.techinterviewer;

import com.techinterviewer.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Builds {@link AppProperties} in plain unit tests, with the defaults of the application. */
public final class TestAppProperties {

    private TestAppProperties() {
    }

    public static AppProperties withJwt(@Nullable String secret, boolean allowEphemeral, String issuer, Duration ttl) {
        return new AppProperties(
                new AppProperties.Cors(List.of("http://localhost:5173")),
                new AppProperties.Security(
                        new AppProperties.Jwt(secret, allowEphemeral, issuer, ttl),
                        new AppProperties.RefreshCookie("refresh_token", true, Duration.ofDays(14), "/api/v1/auth")),
                new AppProperties.RateLimit(10, 20, 10, 60, 10, 10, 10, java.time.Duration.ofMinutes(15)),
                new AppProperties.Interview(Duration.ofSeconds(30), 2, 4),
                new AppProperties.Limits(262_144, 16_384, 2_162_688));
    }
}
