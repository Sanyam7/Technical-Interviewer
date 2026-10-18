package com.techinterviewer.common.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Generator of time-ordered UUIDs, version 7 of RFC 9562. The 128 bits are laid out as
 *
 * <pre>
 *  48 bits  Unix epoch milliseconds (big endian)
 *   4 bits  version = 7
 *  12 bits  rand_a: a counter, so ids created in the same millisecond still increase (RFC 9562, method 1)
 *   2 bits  variant = 10
 *  62 bits  rand_b: random
 * </pre>
 *
 * Ids sort by creation time, which keeps B-tree primary key inserts append-mostly (no random page splits
 * as with UUIDv4). Within one generator instance the ids are strictly increasing, even when the clock stands
 * still or moves backwards. Across processes they are ordered to the millisecond.
 *
 * <p><b>Ordering caveat:</b> {@link UUID#compareTo} compares the halves as <i>signed</i> longs and therefore
 * does not agree with the unsigned byte order PostgreSQL uses for {@code uuid}. Compare the canonical string
 * form (lower-case hex, fixed width) when ordering in Java.
 *
 * <p>The ids are unpredictable only in their random 62 bits and must never be used as secrets or tokens.
 */
public final class UuidV7 {

    private static final Generator SHARED = new Generator(System::currentTimeMillis, new SecureRandom());

    private static final long VERSION_7 = 0x7L << 12;
    private static final long VARIANT_RFC_4122 = 0x8000_0000_0000_0000L;
    private static final long MASK_48_BITS = 0xFFFF_FFFF_FFFFL;
    private static final int COUNTER_BITS = 12;
    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;
    /** The counter starts in the lower half of its range so a burst has room to grow before a millisecond is borrowed. */
    private static final int COUNTER_INITIAL_BOUND = 1 << (COUNTER_BITS - 1);

    private UuidV7() {
    }

    /** A new id from the process-wide generator. */
    public static UUID generate() {
        return SHARED.next();
    }

    /** The creation time (millisecond precision) encoded in an id produced by this class. */
    public static Instant timestampOf(UUID uuid) {
        requireV7(uuid);
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }

    /** True for an RFC 9562 version 7 id. */
    public static boolean isV7(UUID uuid) {
        return uuid.version() == 7 && uuid.variant() == 2;
    }

    private static void requireV7(UUID uuid) {
        if (!isV7(uuid)) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
    }

    /** A generator with its own time source and randomness. Thread-safe. Use it directly only in tests. */
    public static final class Generator {

        private final LongSupplier millis;
        private final RandomGenerator random;
        private final ReentrantLock lock = new ReentrantLock();
        private long lastMillis = -1;
        private int counter;

        public Generator(LongSupplier millis, RandomGenerator random) {
            this.millis = millis;
            this.random = random;
        }

        public UUID next() {
            long timestamp;
            int sequence;
            long randomLow;
            lock.lock();
            try {
                long now = millis.getAsLong() & MASK_48_BITS;
                if (now > lastMillis) {
                    lastMillis = now;
                    counter = random.nextInt(COUNTER_INITIAL_BOUND);
                } else if (counter < COUNTER_MAX) {
                    // Same millisecond, or the clock stepped back: keep the last timestamp and count on.
                    counter++;
                } else {
                    // The counter is exhausted: borrow the next millisecond so the order is still strict.
                    lastMillis = (lastMillis + 1) & MASK_48_BITS;
                    counter = random.nextInt(COUNTER_INITIAL_BOUND);
                }
                timestamp = lastMillis;
                sequence = counter;
                randomLow = random.nextLong();
            } finally {
                lock.unlock();
            }
            long high = (timestamp << 16) | VERSION_7 | sequence;
            long low = VARIANT_RFC_4122 | (randomLow & 0x3FFF_FFFF_FFFF_FFFFL);
            return new UUID(high, low);
        }
    }
}
