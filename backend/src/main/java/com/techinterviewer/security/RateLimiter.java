package com.techinterviewer.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.techinterviewer.common.validation.Require;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;

/**
 * Allows a fixed number of requests per key and time window: a token bucket per key (an IP address, a user id)
 * that holds the whole allowance and refills completely once per window.
 *
 * <p>Memory is bounded two ways. A bucket that was not used for a whole window is full again, so forgetting it
 * changes nothing: buckets expire after one idle window. And there are at most {@code maxKeys} buckets at once;
 * beyond that the cache evicts the least valuable ones, which at worst hands a flood of distinct keys a fresh
 * allowance each (a distributed attack is not what an in-memory limiter can stop).
 *
 * <p>Time comes from the injected {@link Clock} (both for the refill and for the expiry), so tests control it.
 * The state lives in this process: with several instances each one counts on its own.
 */
final class RateLimiter {

    /** The verdict for one request. {@code retryAfter} is zero when the request is allowed. */
    record Decision(boolean allowed, Duration retryAfter) {
    }

    private static final Decision ALLOWED = new Decision(true, Duration.ZERO);

    private final Cache<String, Bucket> buckets;
    private final Bandwidth limit;
    private final TimeMeter meter;

    RateLimiter(int permitsPerWindow, Duration window, int maxKeys, Clock clock) {
        Require.atLeast(permitsPerWindow, 1, "permitsPerWindow");
        Require.atLeast(maxKeys, 1, "maxKeys");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.limit = Bandwidth.builder().capacity(permitsPerWindow).refillIntervally(permitsPerWindow, window).build();
        this.meter = new ClockTimeMeter(clock);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterAccess(window)
                .ticker(() -> meter.currentTimeNanos())
                .executor(Runnable::run)
                .build();
    }

    /** Takes one permit for the key, if there is one. */
    Decision tryAcquire(String key) {
        Bucket bucket = buckets.get(key, k -> Bucket.builder().addLimit(limit).withCustomTimePrecision(meter).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? ALLOWED : new Decision(false, Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    /** Number of keys that currently have a bucket (expired ones are dropped first). */
    long trackedKeys() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    /** Bucket4j's clock, backed by the application clock. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            return Math.multiplyExact(clock.millis(), 1_000_000L);
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
