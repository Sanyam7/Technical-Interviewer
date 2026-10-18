package com.techinterviewer.security;

import com.techinterviewer.common.api.ApiPaths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The single list of endpoints that need no access token. The security filter chain permits exactly these (and
 * ignores any {@code Authorization} header sent to them), and the API documentation marks exactly these as not
 * requiring authentication, so the two cannot drift apart.
 */
public final class PublicEndpoints {

    /**
     * Public reads. HEAD is allowed wherever GET is, so probes that use HEAD get the same answer. The probe groups
     * are listed one by one: an unknown group would make the actuator answer 404 with an empty body, whereas every
     * other unlisted path gets the 401 problem document.
     */
    private static final String[] READ_PATTERNS = {
        ApiPaths.BASE + "/health",
        "/actuator/health",
        "/actuator/health/liveness",
        "/actuator/health/readiness",
        "/actuator/info",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        ApiPaths.BASE + "/catalog/**"
    };

    private static final String[] AUTH_PATTERNS = {
        ApiPaths.BASE + "/auth/register",
        ApiPaths.BASE + "/auth/login",
        ApiPaths.BASE + "/auth/refresh",
        ApiPaths.BASE + "/auth/logout"
    };

    private static final List<PathPattern> READ = parse(READ_PATTERNS);
    private static final List<PathPattern> AUTH = parse(AUTH_PATTERNS);

    private PublicEndpoints() {
    }

    /** Matches the requests that are public. */
    static RequestMatcher requestMatcher() {
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String pattern : READ_PATTERNS) {
            matchers.add(builder.matcher(HttpMethod.GET, pattern));
            matchers.add(builder.matcher(HttpMethod.HEAD, pattern));
        }
        for (String pattern : AUTH_PATTERNS) {
            matchers.add(builder.matcher(HttpMethod.POST, pattern));
        }
        return new OrRequestMatcher(matchers);
    }

    /** Whether an operation (HTTP method and path, templates allowed) needs no access token. */
    public static boolean isPublic(String httpMethod, String path) {
        String method = httpMethod.toUpperCase(Locale.ROOT);
        List<PathPattern> patterns = switch (method) {
            case "GET", "HEAD" -> READ;
            case "POST" -> AUTH;
            default -> List.of();
        };
        PathContainer candidate = PathContainer.parsePath(path);
        return patterns.stream().anyMatch(pattern -> pattern.matches(candidate));
    }

    private static List<PathPattern> parse(String[] patterns) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        return Arrays.stream(patterns).map(parser::parse).toList();
    }
}
