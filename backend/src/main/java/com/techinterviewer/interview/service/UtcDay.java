package com.techinterviewer.interview.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The UTC calendar day that the daily interview limit counts in. */
final class UtcDay {

    private UtcDay() {
    }

    /** Midnight UTC at the start of the day that contains {@code now}. */
    static Instant start(Instant now) {
        return now.truncatedTo(ChronoUnit.DAYS);
    }

    /** Time from {@code now} to the next midnight UTC; the whole day at exactly midnight. */
    static Duration untilNext(Instant now) {
        return Duration.between(now, start(now).plus(1, ChronoUnit.DAYS));
    }
}
