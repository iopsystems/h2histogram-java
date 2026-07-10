package com.iopsystems.h2histogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The README quick-start example, kept compiling and passing. */
class ExampleTest {

    @Test
    void quickStart() {
        Histogram h = new Histogram(7, 64); // groupingPower, maxValuePower

        h.increment(42);
        h.record(1000, 5); // value, count
        h.recordMany(new long[] {12, 15, 900}); // bulk

        assertEquals(9, h.totalCount());

        Bucket p99 = h.percentile(0.99).orElseThrow();
        assertTrue(p99.start() <= p99.end());
        assertTrue(p99.midpoint() > 0);

        // Combine / reduce
        Histogram coarse = h.downsample(4); // fewer buckets, higher error, same total count
        assertEquals(h.totalCount(), coarse.totalCount());
        SparseHistogram sparse = h.toSparse(); // columnar (index, count) form for storage
        assertEquals(h.totalCount(), sparse.totalCount());

        // Fast repeated quantile queries
        CumulativeHistogram c = h.toCumulative(); // read-only; also SparseHistogram.toCumulative()
        Bucket cumulativeP99 = c.percentile(0.99).orElseThrow(); // O(log n) binary search
        assertEquals(p99, cumulativeP99);
        assertTrue(c.mean().isPresent()); // midpoint-estimated mean, computed once
        assertTrue(c.bucketQuantileRange(0).isPresent());
        for (BucketWithQuantiles bq : c.bucketsWithQuantiles()) {
            assertTrue(bq.lowerQuantile() <= bq.upperQuantile());
        }
    }
}
