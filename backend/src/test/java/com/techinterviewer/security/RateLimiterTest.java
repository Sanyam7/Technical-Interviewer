package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Time is the {@link TestClock}: nothing here sleeps. */
class RateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final TestClock clock = new TestClock();

    private RateLimiter limiter(int permits) {
        return new RateLimiter(permits, MINUTE, 1000, clock);
    }

    private static int allowedOutOf(RateLimiter limiter, String key, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(key).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void allowsExactlyThePermitsOfTheWindowAndRefusesTheRest() {
        RateLimiter limiter = limiter(10);

        assertThat(allowedOutOf(limiter, "10.0.0.1", 25)).isEqualTo(10);
    }

    @Test
    void theFirstRefusedRequestIsRefusedForTheRestOfTheWindow() {
        RateLimiter limiter = limiter(2);
        limiter.tryAcquire("k");
        limiter.tryAcquire("k");

        RateLimiter.Decision refused = limiter.tryAcquire("k");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(MINUTE);
    }

    @Test
    void retryAfterShrinksAsTheWindowPasses() {
        RateLimiter limiter = limiter(1);
        limiter.tryAcquire("k");

        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.tryAcquire("k").retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void anAllowedRequestHasNoRetryDelay() {
        assertThat(limiter(1).tryAcquire("k").retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    void theWholeAllowanceIsBackOnceTheWindowHasPassedButNotBefore() {
        RateLimiter limiter = limiter(3);
        assertThat(allowedOutOf(limiter, "k", 5)).isEqualTo(3);

        clock.advance(MINUTE.minusMillis(1));
        assertThat(limiter.tryAcquire("k").allowed()).as("one millisecond early").isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(allowedOutOf(limiter, "k", 5)).as("after the window").isEqualTo(3);
    }

    @Test
    void keysDoNotShareAnAllowance() {
        RateLimiter limiter = limiter(2);

        assertThat(allowedOutOf(limiter, "a", 5)).isEqualTo(2);
        assertThat(allowedOutOf(limiter, "b", 5)).isEqualTo(2);
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void aBucketNotUsedForAWholeWindowIsForgotten() {
        RateLimiter limiter = limiter(5);
        limiter.tryAcquire("idle");
        limiter.tryAcquire("busy");
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(59));
        limiter.tryAcquire("busy");
        clock.advance(Duration.ofSeconds(2));

        assertThat(limiter.trackedKeys()).as("idle for 61 s is gone, busy was used 2 s ago").isEqualTo(1);
    }

    @Test
    void usingABucketRestartsItsIdleTimer() {
        RateLimiter limiter = limiter(5);
        limiter.tryAcquire("k");

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(50));
            limiter.tryAcquire("k");
        }

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void aForgottenBucketComesBackFull() {
        RateLimiter limiter = limiter(2);
        assertThat(allowedOutOf(limiter, "k", 3)).isEqualTo(2);

        clock.advance(Duration.ofMinutes(5));

        assertThat(limiter.trackedKeys()).isZero();
        assertThat(allowedOutOf(limiter, "k", 3)).isEqualTo(2);
    }

    @Test
    void theNumberOfTrackedKeysIsBounded() {
        RateLimiter limiter = new RateLimiter(5, MINUTE, 50, clock);

        for (int i = 0; i < 5000; i++) {
            limiter.tryAcquire("203.0.113." + i);
        }

        assertThat(limiter.trackedKeys()).isBetween(1L, 50L);
    }

    @Test
    void concurrentRequestsNeverExceedTheAllowance() throws Exception {
        RateLimiter limiter = limiter(10);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < 10; j++) {
                    if (limiter.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(10);
    }

    @Test
    void invalidSettingsAreRefused() {
        assertThatThrownBy(() -> new RateLimiter(0, MINUTE, 10, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(1, MINUTE, 0, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(1, Duration.ZERO, 10, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(1, Duration.ofSeconds(-1), 10, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
