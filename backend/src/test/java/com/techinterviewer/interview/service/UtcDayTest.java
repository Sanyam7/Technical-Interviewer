package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UtcDayTest {

    @Test
    void theDayStartsAtMidnightUtc() {
        assertThat(UtcDay.start(Instant.parse("2026-10-05T14:03:27.123Z"))).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(UtcDay.start(Instant.parse("2026-10-05T23:59:59.999999999Z"))).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void midnightItselfBelongsToTheNewDay() {
        assertThat(UtcDay.start(Instant.parse("2026-10-06T00:00:00Z"))).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void theWaitUntilTheNextDayIsTheTimeToTheNextMidnight() {
        assertThat(UtcDay.untilNext(Instant.parse("2026-10-05T23:00:00Z"))).isEqualTo(Duration.ofHours(1));
        assertThat(UtcDay.untilNext(Instant.parse("2026-10-05T12:00:00Z"))).isEqualTo(Duration.ofHours(12));
        assertThat(UtcDay.untilNext(Instant.parse("2026-10-05T23:59:59Z"))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void exactlyAtMidnightTheWholeDayRemains() {
        assertThat(UtcDay.untilNext(Instant.parse("2026-10-06T00:00:00Z"))).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void theNextDayIsCorrectAcrossMonthAndYearEnds() {
        assertThat(UtcDay.untilNext(Instant.parse("2026-12-31T22:00:00Z"))).isEqualTo(Duration.ofHours(2));
        assertThat(UtcDay.start(Instant.parse("2028-02-29T18:00:00Z"))).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        assertThat(UtcDay.untilNext(Instant.parse("2028-02-28T18:00:00Z"))).isEqualTo(Duration.ofHours(6));
        assertThat(UtcDay.untilNext(Instant.parse("2028-02-29T18:00:00Z"))).isEqualTo(Duration.ofHours(6));
    }
}
