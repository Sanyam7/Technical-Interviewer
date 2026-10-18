package com.techinterviewer.security;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts the requests that match, per key, against a {@link RateLimiter} and answers 429 (problem document with
 * {@code Retry-After}) once the allowance is used up. Requests that do not match, and matching requests that have
 * no key (an anonymous caller where a user is needed), pass untouched.
 *
 * <p>This class is deliberately not a Spring bean: a {@code Filter} bean would also be registered with the servlet
 * container, outside Spring Security, where for example the authenticated user is not known yet. Spring Security's
 * filter chain adds these filters at their precise position (see {@link SecurityConfig}).
 */
final class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMITED_DETAIL = "Too many requests. Wait before trying again.";

    private final String name;
    private final RequestMatcher matcher;
    private final Function<HttpServletRequest, @Nullable String> keyOf;
    private final RateLimiter limiter;
    private final ProblemResponseWriter problems;

    private RateLimitFilter(
            String name,
            RequestMatcher matcher,
            Function<HttpServletRequest, @Nullable String> keyOf,
            RateLimiter limiter,
            ProblemResponseWriter problems) {
        this.name = name;
        this.matcher = matcher;
        this.keyOf = keyOf;
        this.limiter = limiter;
        this.problems = problems;
    }

    /** Limits the matching requests per client address, as the servlet container reports it. */
    static RateLimitFilter perClientAddress(
            String name, RequestMatcher matcher, RateLimiter limiter, ProblemResponseWriter problems) {
        return new RateLimitFilter(name, matcher, HttpServletRequest::getRemoteAddr, limiter, problems);
    }

    /**
     * Limits the matching requests per authenticated user. Must run after bearer authentication, which is what
     * puts the user into the security context.
     */
    static RateLimitFilter perUser(
            String name, RequestMatcher matcher, RateLimiter limiter, ProblemResponseWriter problems) {
        return new RateLimitFilter(name, matcher, request -> authenticatedUserId(), limiter, problems);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matcher.matches(request);
    }

    /** Two instances of this class sit in one filter chain, so each needs its own once-per-request marker. */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return RateLimitFilter.class.getName() + "." + name + ".FILTERED";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = keyOf.apply(request);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision decision = limiter.tryAcquire(key);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        problems.write(request, response,
                new ApiException(ErrorCode.RATE_LIMITED, LIMITED_DETAIL).withRetryAfter(decision.retryAfter()));
    }

    private static @Nullable String authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? token.getName() : null;
    }
}
