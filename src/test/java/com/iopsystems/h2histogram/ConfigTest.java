package com.iopsystems.h2histogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Expected values here are copied verbatim from src/config.rs in
 * https://github.com/iopsystems/histogram so we are guaranteed bit-identical
 * bucketing.
 */
class ConfigTest {
    private static final long U64_MAX = -1L; // 2^64 - 1, unsigned

    @Test
    void totalBuckets() {
        assertEquals(252, new Config(2, 64).totalBuckets());
        assertEquals(7424, new Config(7, 64).totalBuckets());
        assertEquals(835_584, new Config(14, 64).totalBuckets());
        assertEquals(12, new Config(2, 4).totalBuckets());
    }

    @Test
    void valueToIndex() {
        Config c = new Config(7, 64);
        assertEquals(0, c.valueToIndex(0));
        assertEquals(1, c.valueToIndex(1));
        assertEquals(256, c.valueToIndex(256));
        assertEquals(256, c.valueToIndex(257));
        assertEquals(257, c.valueToIndex(258));
        assertEquals(384, c.valueToIndex(512));
        assertEquals(384, c.valueToIndex(515));
        assertEquals(385, c.valueToIndex(516));
        assertEquals(512, c.valueToIndex(1024));
        assertEquals(512, c.valueToIndex(1031));
        assertEquals(513, c.valueToIndex(1032));
        assertEquals(7423, c.valueToIndex(U64_MAX - 1));
        assertEquals(7423, c.valueToIndex(U64_MAX));
    }

    @Test
    void indexToLowerBound() {
        Config c = new Config(7, 64);
        assertEquals(0, c.indexToLowerBound(0));
        assertEquals(1, c.indexToLowerBound(1));
        assertEquals(256, c.indexToLowerBound(256));
        assertEquals(512, c.indexToLowerBound(384));
        assertEquals(1024, c.indexToLowerBound(512));
        assertEquals(Long.parseUnsignedLong("18374686479671623680"),
                c.indexToLowerBound(7423));
    }

    @Test
    void indexToUpperBound() {
        Config c = new Config(7, 64);
        assertEquals(0, c.indexToUpperBound(0));
        assertEquals(1, c.indexToUpperBound(1));
        assertEquals(257, c.indexToUpperBound(256));
        assertEquals(515, c.indexToUpperBound(384));
        assertEquals(1031, c.indexToUpperBound(512));
        assertEquals(U64_MAX, c.indexToUpperBound(7423));
    }

    @Test
    void roundtripValueIndexRange() {
        Config c = new Config(7, 64);
        long[] values = {0, 1, 5, 127, 128, 255, 256, 257, 999, 1_000_000, (1L << 40) + 7};
        for (long v : values) {
            int idx = c.valueToIndex(v);
            long lo = c.indexToLowerBound(idx);
            long hi = c.indexToUpperBound(idx);
            assertTrue(Long.compareUnsigned(lo, v) <= 0 && Long.compareUnsigned(v, hi) <= 0,
                    "value " + v + " landed in bucket " + idx + " [" + lo + ", " + hi + "]");
        }
    }

    @Test
    void error() {
        assertEquals(100.0 / 128, new Config(7, 64).error(), 1e-12);
        // No logarithmic buckets -> zero error.
        assertEquals(0.0, new Config(3, 4).error());
    }

    @Test
    void invalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new Config(7, 65));
        assertThrows(IllegalArgumentException.class, () -> new Config(64, 64));
        assertThrows(IllegalArgumentException.class, () -> new Config(10, 5));
        assertThrows(IllegalArgumentException.class, () -> new Config(-1, 4));
    }

    @Test
    void fromTotalBuckets() {
        Config c = Config.fromTotalBuckets(7424, 64);
        assertEquals(7, c.groupingPower());
        assertEquals(64, c.maxValuePower());
        // Rezolus default.
        Config c2 = Config.fromTotalBuckets(496, 64);
        assertEquals(3, c2.groupingPower());
        assertThrows(IllegalArgumentException.class, () -> Config.fromTotalBuckets(7425, 64));
    }

    @Test
    void outOfRange() {
        Config c = new Config(2, 4); // max = 15
        assertEquals(c.totalBuckets() - 1, c.valueToIndex(15));
        assertThrows(IllegalArgumentException.class, () -> c.valueToIndex(16));
    }
}
