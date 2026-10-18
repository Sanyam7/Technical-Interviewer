package com.techinterviewer.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.techinterviewer.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A limit on failed sign-ins <b>per e-mail address</b> (SECURITY.md, AUTH-2). The per-address limit of the auth endpoints stops one
 * machine; guessing one account's password from many addresses is not slowed by it. After {@code login-failures-per-account}
 * failures within {@code login-failure-window}, sign-in for that address answers 429 with {@code Retry-After} until the window
 * after the first of them has passed, even with the right password. A success clears the count.
 *
 * <p>Failures are counted for every address that is typed, registered or not, so the 429 does not tell a guesser which addresses
 * have an account. The price: whoever knows an address can keep its owner from signing in for one window; the answer is
 * temporary, and the owner's existing sessions are not affected.
 *
 * <p>In memory like the other limits (one instance); at most {@value #MAX_TRACKED_ADDRESSES} addresses are tracked, the oldest
 * forgotten first. Time comes from the injected clock.
 */
@Component
class LoginThrottle {

    static final int MAX_TRACKED_ADDRESSES = 100_000;

    private record Failures(int count, Instant windowEnds) {
    }

    private final Cache<String, Failures> failures;
    private final int allowed;
    private final Duration window;
    private final Clock clock;

    LoginThrottle(AppProperties properties, Clock clock) {
        this.allowed = properties.rateLimit().loginFailuresPerAccount();
        this.window = properties.rateLimit().loginFailureWindow();
        this.clock = clock;
        this.failures = Caffeine.newBuilder().maximumSize(MAX_TRACKED_ADDRESSES).expireAfterWrite(window.plusSeconds(1))
                .ticker(() -> Math.multiplyExact(clock.millis(), 1_000_000L)).executor(Runnable::run).build();
    }

    /** How long sign-in for this address is refused; empty when an attempt may be made. */
    Optional<Duration> blockedFor(String email) {
        Failures known = failures.getIfPresent(key(email));
        Instant now = clock.instant();
        if (known == null || known.count() < allowed || !now.isBefore(known.windowEnds())) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, known.windowEnds()));
    }

    void failed(String email) {
        Instant now = clock.instant();
        failures.asMap().compute(key(email), (address, known) -> known == null || !now.isBefore(known.windowEnds())
                ? new Failures(1, now.plus(window)) : new Failures(known.count() + 1, known.windowEnds()));
    }

    void succeeded(String email) {
        failures.invalidate(key(email));
    }

    /** The address as the account store compares it (case-insensitively), bounded so a huge string cannot fill the memory. */
    private static String key(String email) {
        String folded = email.strip().toLowerCase(Locale.ROOT);
        return folded.length() <= 320 ? folded : folded.substring(0, 320);
    }
}
