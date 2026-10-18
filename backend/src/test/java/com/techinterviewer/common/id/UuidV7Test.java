package com.techinterviewer.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    /** The canonical lower-case hex form sorts exactly like PostgreSQL's unsigned byte order of a uuid. */
    private static String sortKey(UUID uuid) {
        return uuid.toString();
    }

    private static UuidV7.Generator generator(AtomicLong clock, long seed) {
        return new UuidV7.Generator(clock::get, new Random(seed));
    }

    @Test
    void versionAndVariantBitsFollowRfc9562() {
        UuidV7.Generator generator = generator(new AtomicLong(1_760_000_000_000L), 1);

        for (int i = 0; i < 1000; i++) {
            UUID uuid = generator.next();

            assertThat(uuid.version()).isEqualTo(7);
            assertThat(uuid.variant()).isEqualTo(2);
            // Canonical text: 8-4-4-4-12, the third group starts with 7 and the fourth with 8, 9, a or b.
            assertThat(uuid.toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
            assertThat(UuidV7.isV7(uuid)).isTrue();
        }
    }

    @Test
    void firstFortyEightBitsAreTheUnixMillisecondTimestamp() {
        long millis = 1_760_000_123_456L;
        UuidV7.Generator generator = generator(new AtomicLong(millis), 2);

        UUID uuid = generator.next();

        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(millis);
        assertThat(UuidV7.timestampOf(uuid)).isEqualTo(Instant.ofEpochMilli(millis));
        assertThat(uuid.toString()).startsWith(String.format("%08x-%04x", millis >>> 16, millis & 0xFFFF));
    }

    @Test
    void idsSortByCreationTimeAcrossMilliseconds() {
        AtomicLong clock = new AtomicLong(1_760_000_000_000L);
        UuidV7.Generator generator = generator(clock, 3);
        List<UUID> ids = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            ids.add(generator.next());
            clock.addAndGet(1 + (i % 7) * 40);
        }

        assertThat(ids).isSortedAccordingTo((a, b) -> sortKey(a).compareTo(sortKey(b)));
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    }

    @Test
    void idsCreatedInTheSameMillisecondStillIncrease() {
        UuidV7.Generator generator = generator(new AtomicLong(1_760_000_000_000L), 4);
        List<UUID> ids = new ArrayList<>();

        for (int i = 0; i < 1500; i++) {
            ids.add(generator.next());
        }

        for (int i = 1; i < ids.size(); i++) {
            assertThat(sortKey(ids.get(i))).isGreaterThan(sortKey(ids.get(i - 1)));
        }
    }

    @Test
    void exhaustingTheCounterBorrowsTheNextMillisecondInsteadOfGoingBackwards() {
        AtomicLong clock = new AtomicLong(1_760_000_000_000L);
        UuidV7.Generator generator = generator(clock, 5);
        UUID previous = generator.next();

        // 10 000 ids in a single millisecond is more than the 12-bit counter (4096) can order.
        for (int i = 0; i < 10_000; i++) {
            UUID next = generator.next();
            assertThat(sortKey(next)).isGreaterThan(sortKey(previous));
            previous = next;
        }

        assertThat(UuidV7.timestampOf(previous)).isAfter(Instant.ofEpochMilli(1_760_000_000_000L));
    }

    @Test
    void aClockThatMovesBackwardsNeverProducesASmallerId() {
        AtomicLong clock = new AtomicLong(1_760_000_000_000L);
        UuidV7.Generator generator = generator(clock, 6);
        UUID before = generator.next();

        clock.addAndGet(-5_000);
        UUID after = generator.next();

        assertThat(sortKey(after)).isGreaterThan(sortKey(before));
        assertThat(UuidV7.timestampOf(after)).isEqualTo(UuidV7.timestampOf(before));
    }

    @Test
    void whenTheClockCatchesUpTheTimestampFollowsItAgain() {
        AtomicLong clock = new AtomicLong(1_760_000_000_000L);
        UuidV7.Generator generator = generator(clock, 7);
        generator.next();
        clock.addAndGet(-5_000);
        generator.next();

        clock.set(1_760_000_009_000L);

        assertThat(UuidV7.timestampOf(generator.next())).isEqualTo(Instant.ofEpochMilli(1_760_000_009_000L));
    }

    @Test
    void randomPartDiffersBetweenGeneratorsWithTheSameClock() {
        UUID a = generator(new AtomicLong(1_760_000_000_000L), 8).next();
        UUID b = generator(new AtomicLong(1_760_000_000_000L), 9).next();

        assertThat(a).isNotEqualTo(b);
        assertThat(a.getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL).isNotEqualTo(b.getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL);
    }

    @Test
    void idsAreUniqueUnderConcurrency() throws Exception {
        int threads = 16;
        int perThread = 20_000;
        AtomicLong clock = new AtomicLong(1_760_000_000_000L);
        UuidV7.Generator generator = generator(clock, 10);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<UUID>> task = () -> {
                    List<UUID> ids = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        ids.add(generator.next());
                    }
                    return ids;
                };
                futures.add(pool.submit(task));
            }
            Set<UUID> all = new HashSet<>();
            for (Future<List<UUID>> future : futures) {
                List<UUID> ids = future.get();
                // Within one thread the order is strictly increasing even though the clock never moved.
                for (int i = 1; i < ids.size(); i++) {
                    assertThat(sortKey(ids.get(i))).isGreaterThan(sortKey(ids.get(i - 1)));
                }
                all.addAll(ids);
            }
            assertThat(all).hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSharedGeneratorUsesTheCurrentTime() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.generate();
        long after = System.currentTimeMillis();

        assertThat(UuidV7.isV7(uuid)).isTrue();
        assertThat(UuidV7.timestampOf(uuid).toEpochMilli()).isBetween(before - 1, after + 5000);
    }

    @Test
    void timestampOfRefusesAnIdThatIsNotVersion7() {
        assertThatThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThat(UuidV7.isV7(UUID.randomUUID())).isFalse();
    }

    @Test
    void javaUuidComparisonIsNotTheDatabaseOrderWhichIsWhyTheTextFormIsUsed() {
        // Documents the caveat in UuidV7's javadoc: the variant bits make the low half negative as a signed long.
        UuidV7.Generator generator = generator(new AtomicLong(1_760_000_000_000L), 11);
        UUID first = generator.next();
        UUID second = generator.next();

        assertThat(sortKey(first)).isLessThan(sortKey(second));
        assertThat(first.getLeastSignificantBits()).isNegative();
    }
}
