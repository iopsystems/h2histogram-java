package com.iopsystems.h2histogram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class CumulativeHistogramTest {

    @Test
    void fromHistogram() {
        Histogram h = new Histogram(7, 64);
        h.record(1, 2);
        h.record(500, 3);
        h.record(1_000_000, 5);

        CumulativeHistogram c = h.toCumulative();
        assertEquals(10, c.totalCount());
        assertEquals(3, c.size());
        // Cumulative counts are prefix sums; last equals total.
        assertArrayEquals(new long[] {2, 5, 10}, c.count());
    }

    @Test
    void percentileMatchesDense() {
        Histogram h = new Histogram(7, 64);
        for (long i = 1; i <= 1000; i++) {
            h.increment(i);
        }
        CumulativeHistogram c = h.toCumulative();
        for (double q : new double[] {0.0, 0.1, 0.5, 0.9, 0.99, 1.0}) {
            Optional<Bucket> dense = h.percentile(q);
            Optional<Bucket> cumulative = c.percentile(q);
            assertTrue(dense.isPresent() && cumulative.isPresent(), "empty bucket at q=" + q);
            assertEquals(dense.get().start(), cumulative.get().start(), "start at q=" + q);
            assertEquals(dense.get().end(), cumulative.get().end(), "end at q=" + q);
        }
    }

    @Test
    void empty() {
        Histogram h = new Histogram(7, 64);
        CumulativeHistogram c = h.toCumulative();
        assertTrue(c.isEmpty());
        assertTrue(c.percentile(0.5).isEmpty());
        assertTrue(c.mean().isEmpty());
    }

    @Test
    void mean() {
        Histogram h = new Histogram(7, 64);
        // All in the linear (exact) region so the midpoint mean is exact.
        h.record(10, 1);
        h.record(20, 1);
        h.record(30, 1);
        OptionalDouble m = h.toCumulative().mean();
        assertTrue(m.isPresent());
        assertEquals(20.0, m.getAsDouble(), 1e-9);
    }

    @Test
    void fromParts() {
        Config cfg = new Config(7, 64);
        CumulativeHistogram c = CumulativeHistogram.fromParts(
                cfg, new int[] {1, 256}, new long[] {3, 8});
        assertEquals(8, c.totalCount());
        // invalid: zero count
        assertThrows(IllegalArgumentException.class,
                () -> CumulativeHistogram.fromParts(cfg, new int[] {1}, new long[] {0}));
        // invalid: decreasing
        assertThrows(IllegalArgumentException.class,
                () -> CumulativeHistogram.fromParts(cfg, new int[] {1, 2}, new long[] {5, 3}));
        // invalid: not ascending
        assertThrows(IllegalArgumentException.class,
                () -> CumulativeHistogram.fromParts(cfg, new int[] {2, 1}, new long[] {1, 2}));
    }

    @Test
    void bucketQuantileRange() {
        Histogram h = new Histogram(7, 64);
        h.record(10, 2);
        h.record(20, 2);
        CumulativeHistogram c = h.toCumulative();

        Optional<BucketWithQuantiles> first = c.bucketQuantileRange(0);
        assertTrue(first.isPresent());
        assertEquals(0.0, first.get().lowerQuantile(), 1e-9);
        assertEquals(0.5, first.get().upperQuantile(), 1e-9);

        Optional<BucketWithQuantiles> second = c.bucketQuantileRange(1);
        assertTrue(second.isPresent());
        assertEquals(0.5, second.get().lowerQuantile(), 1e-9);
        assertEquals(1.0, second.get().upperQuantile(), 1e-9);

        assertTrue(c.bucketQuantileRange(99).isEmpty());
    }

    @Test
    void bucketsWithQuantiles() {
        Histogram h = new Histogram(7, 64);
        h.record(10, 2);
        h.record(20, 2);
        var withQuantiles = h.toCumulative().bucketsWithQuantiles();
        assertEquals(2, withQuantiles.size());
        assertEquals(0.0, withQuantiles.get(0).lowerQuantile(), 1e-9);
        assertEquals(0.5, withQuantiles.get(0).upperQuantile(), 1e-9);
        assertEquals(0.5, withQuantiles.get(1).lowerQuantile(), 1e-9);
        assertEquals(1.0, withQuantiles.get(1).upperQuantile(), 1e-9);
        assertEquals(2, withQuantiles.get(0).bucket().count());
    }

    @Test
    void sparseToCumulative() {
        Histogram h = new Histogram(7, 64);
        h.record(1, 1);
        h.record(500, 3);
        SparseHistogram sparse = h.toSparse();
        CumulativeHistogram c = sparse.toCumulative();
        assertEquals(4, c.totalCount());
        assertEquals(h, c.toDense());
        assertFalse(c.isEmpty());
    }
}
