package com.techinterviewer.security;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * The request limits of the API (decision D6), built from {@code app.rate-limit.*}:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/register|login|refresh}: {@code auth-per-minute} per client address, one shared
 *       allowance for the three endpoints.
 *   <li>{@code POST /api/v1/interviews/{id}/answers}: {@code answers-per-minute} per authenticated user.
 *   <li>{@code POST /api/v1/interviews/{id}/questions/{questionId}/runs}: {@code code-runs-per-minute} per authenticated user,
 *       because every run starts a sandbox container.
 *   <li>{@code PUT /api/v1/profile/resume}: {@code resume-uploads-per-hour} per authenticated user, because every upload is a
 *       model call over a whole résumé.
 *   <li>every other state-changing request ({@code POST}, {@code PUT}, {@code DELETE} outside {@code /auth/} and the
 *       answers endpoint): {@code writes-per-minute} per authenticated user, so that endpoints that will call an AI
 *       provider (finish, next question, report retry) cannot be hammered.
 * </ul>
 *
 * The daily quota of interviews ({@code app.rate-limit.interviews-per-day}) is not a request limit: the interview
 * service counts the user's rows in the database and answers 429 {@code DAILY_INTERVIEW_LIMIT}.
 */
@Component
class RateLimiters {

    static final Duration WINDOW = Duration.ofMinutes(1);

    /** Upper bound of the buckets kept per limiter (a bucket is a few hundred bytes). */
    static final int MAX_TRACKED_KEYS = 100_000;

    private final RateLimitFilter authFilter;
    private final RateLimitFilter answersFilter;
    private final RateLimitFilter writesFilter;
    private final RateLimitFilter codeRunsFilter;
    private final RateLimitFilter resumeUploadsFilter;

    RateLimiters(AppProperties properties, Clock clock, ProblemResponseWriter problems) {
        AppProperties.RateLimit limits = properties.rateLimit();
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        this.authFilter = RateLimitFilter.perClientAddress(
                "auth",
                new OrRequestMatcher(List.of(
                        paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/auth/register"),
                        paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/auth/login"),
                        paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/auth/refresh"))),
                new RateLimiter(limits.authPerMinute(), WINDOW, MAX_TRACKED_KEYS, clock),
                problems);
        this.answersFilter = RateLimitFilter.perUser(
                "answers",
                paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/interviews/*/answers"),
                new RateLimiter(limits.answersPerMinute(), WINDOW, MAX_TRACKED_KEYS, clock),
                problems);
        RequestMatcher answers = paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/interviews/*/answers");
        RequestMatcher codeRuns = paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/interviews/*/questions/*/runs");
        this.codeRunsFilter = RateLimitFilter.perUser(
                "code-runs",
                codeRuns,
                new RateLimiter(limits.codeRunsPerMinute(), WINDOW, MAX_TRACKED_KEYS, clock),
                problems);
        RequestMatcher resumeUploads = paths.matcher(HttpMethod.PUT, ApiPaths.BASE + "/profile/resume");
        this.resumeUploadsFilter = RateLimitFilter.perUser(
                "resume-uploads",
                resumeUploads,
                new RateLimiter(limits.resumeUploadsPerHour(), Duration.ofHours(1), MAX_TRACKED_KEYS, clock),
                problems);
        RequestMatcher authentication = paths.matcher(ApiPaths.BASE + "/auth/**");
        this.writesFilter = RateLimitFilter.perUser(
                "writes",
                new AndRequestMatcher(
                        new OrRequestMatcher(List.of(
                                paths.matcher(HttpMethod.POST, ApiPaths.BASE + "/**"),
                                paths.matcher(HttpMethod.PUT, ApiPaths.BASE + "/**"),
                                paths.matcher(HttpMethod.DELETE, ApiPaths.BASE + "/**"))),
                        new NegatedRequestMatcher(new OrRequestMatcher(List.of(answers, codeRuns, resumeUploads, authentication)))),
                new RateLimiter(limits.writesPerMinute(), WINDOW, MAX_TRACKED_KEYS, clock),
                problems);
    }

    /** Runs before authentication: the auth endpoints are public. */
    RateLimitFilter authFilter() {
        return authFilter;
    }

    /** Must run after bearer authentication: it limits per authenticated user. */
    RateLimitFilter answersFilter() {
        return answersFilter;
    }

    /** Must run after bearer authentication: it limits per authenticated user. */
    RateLimitFilter writesFilter() {
        return writesFilter;
    }

    /** Must run after bearer authentication: it limits per authenticated user. */
    RateLimitFilter codeRunsFilter() {
        return codeRunsFilter;
    }

    /** Must run after bearer authentication: it limits per authenticated user. */
    RateLimitFilter resumeUploadsFilter() {
        return resumeUploadsFilter;
    }
}
