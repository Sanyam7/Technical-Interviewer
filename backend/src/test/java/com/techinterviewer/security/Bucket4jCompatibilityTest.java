package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Bucket4j (the jdk17 build, 8.21.0) works on this project's Java 21 and behaves as the rate limits need: a fixed
 * allowance per interval, a retry delay for the caller (the {@code Retry-After} header) and an exact refill.
 * The limits themselves (auth per IP, answers per user) are wired by the authentication and interview lanes.
 */
class Bucket4jCompatibilityTest {

    private final AtomicLong nanos = new AtomicLong();
    private final TimeMeter meter = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };

    private Bucket perMinute(int limit) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limit).refillIntervally(limit, Duration.ofMinutes(1)).build())
                .withCustomTimePrecision(meter)
                .build();
    }

    @Test
    void allowsExactlyTheLimitPerMinuteAndTellsTheCallerHowLongToWait() {
        Bucket bucket = perMinute(10);

        for (int i = 0; i < 10; i++) {
            assertThat(bucket.tryConsume(1)).as("request %d", i + 1).isTrue();
        }
        nanos.addAndGet(Duration.ofSeconds(20).toNanos());
        ConsumptionProbe refused = bucket.tryConsumeAndReturnRemaining(1);

        assertThat(refused.isConsumed()).isFalse();
        assertThat(Duration.ofNanos(refused.getNanosToWaitForRefill())).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void refillsTheWholeAllowanceWhenTheIntervalHasPassed() {
        Bucket bucket = perMinute(3);
        assertThat(bucket.tryConsume(3)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();

        nanos.addAndGet(Duration.ofMinutes(1).toNanos());

        assertThat(bucket.tryConsume(3)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();
    }

    @Test
    void bucketsAreIndependentOfEachOther() {
        Bucket first = perMinute(1);
        Bucket second = perMinute(1);

        assertThat(first.tryConsume(1)).isTrue();
        assertThat(first.tryConsume(1)).isFalse();
        assertThat(second.tryConsume(1)).isTrue();
    }
}
