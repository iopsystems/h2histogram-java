package systems.iop.h2histogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistogramTest {

    @Test
    void incrementAndTotal() {
        Histogram h = new Histogram(7, 64);
        for (long i = 0; i <= 100; i++) {
            h.increment(i);
        }
        assertEquals(101, h.totalCount());
    }

    @Test
    void recordWithCount() {
        Histogram h = new Histogram(7, 64);
        h.record(100, 5);
        assertEquals(5, h.totalCount());
        int idx = h.config().valueToIndex(100);
        assertEquals(5, h.bucketCounts()[idx]);
    }

    @Test
    void percentileExactLowRange() {
        // In the linear region (values < cutoff) buckets have width 1, so
        // percentiles are exact.
        Histogram h = new Histogram(7, 64);
        for (long i = 1; i <= 100; i++) {
            h.increment(i);
        }
        assertBucketRange(h.percentile(0.5), 50, 50);
        assertBucketRange(h.percentile(1.0), 100, 100);
        assertBucketRange(h.percentile(0.0), 1, 1);
    }

    private static void assertBucketRange(Optional<Bucket> bucket, long start, long end) {
        assertTrue(bucket.isPresent());
        assertEquals(start, bucket.get().start());
        assertEquals(end, bucket.get().end());
    }

    @Test
    void percentileEmpty() {
        Histogram h = new Histogram(7, 64);
        assertTrue(h.percentile(0.5).isEmpty());
        assertTrue(h.percentiles(0.5, 0.9).isEmpty());
    }

    @Test
    void percentilesOrderPreserved() {
        Histogram h = new Histogram(7, 64);
        for (long i = 0; i < 1000; i++) {
            h.increment(i);
        }
        List<PercentileResult> results = h.percentiles(0.9, 0.5, 0.99);
        double[] want = {0.9, 0.5, 0.99};
        assertEquals(want.length, results.size());
        for (int i = 0; i < want.length; i++) {
            assertEquals(want[i], results.get(i).percentile());
        }
    }

    @Test
    void percentileInvalid() {
        Histogram h = new Histogram(7, 64);
        h.increment(1);
        assertThrows(IllegalArgumentException.class, () -> h.percentile(1.5));
        assertThrows(IllegalArgumentException.class, () -> h.percentile(-0.1));
    }

    @Test
    void merge() {
        Histogram a = new Histogram(7, 64);
        Histogram b = new Histogram(7, 64);
        a.record(10, 3);
        b.record(10, 4);
        b.record(2000, 1);
        Histogram merged = a.merge(b);
        assertEquals(8, merged.totalCount());
        int idx = merged.config().valueToIndex(10);
        assertEquals(7, merged.bucketCounts()[idx]);
    }

    @Test
    void mergeIncompatible() {
        Histogram a = new Histogram(7, 64);
        Histogram b = new Histogram(6, 64);
        assertThrows(IllegalArgumentException.class, () -> a.merge(b));
    }

    @Test
    void subtract() {
        Histogram a = new Histogram(7, 64);
        Histogram b = new Histogram(7, 64);
        a.record(10, 5);
        b.record(10, 2);
        Histogram diff = a.subtract(b);
        assertEquals(3, diff.totalCount());
        assertThrows(IllegalArgumentException.class, () -> b.subtract(a));
    }

    @Test
    void fromBucketsRoundtrip() {
        Histogram h = new Histogram(3, 64);
        h.record(5, 2);
        h.record(1000, 7);
        Histogram h2 = Histogram.fromBuckets(3, 64, h.bucketCounts());
        assertEquals(h, h2);
    }

    @Test
    void fromBucketsWrongLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Histogram.fromBuckets(7, 64, new long[] {0, 0, 0}));
    }

    @Test
    void downsample() {
        Histogram h = new Histogram(7, 64);
        for (long i = 0; i < 10000; i++) {
            h.increment(i);
        }
        Histogram coarse = h.downsample(3);
        assertEquals(3, coarse.config().groupingPower());
        assertEquals(h.totalCount(), coarse.totalCount());
        assertThrows(IllegalArgumentException.class, () -> h.downsample(7));
    }

    @Test
    void sparseRoundtrip() {
        Histogram h = new Histogram(7, 64);
        h.record(1, 1);
        h.record(500, 3);
        h.record(999999, 2);
        SparseHistogram sparse = h.toSparse();
        assertEquals(h.totalCount(), sparse.totalCount());
        assertEquals(3, sparse.size());
        int prev = -1;
        for (int i : sparse.index()) {
            assertTrue(i > prev, "sparse indices not strictly ascending");
            prev = i;
        }
        assertEquals(h, sparse.toDense());
    }

    @Test
    void sparseFromPartsValidation() {
        Config c = new Config(7, 64);
        assertThrows(IllegalArgumentException.class,
                () -> SparseHistogram.fromParts(c, new int[] {1, 2}, new long[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> SparseHistogram.fromParts(c, new int[] {2, 1}, new long[] {1, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> SparseHistogram.fromParts(c, new int[] {999999999}, new long[] {1}));
    }

    @Test
    void recordManyMatchesLoop() {
        long[] base = {0, 1, 2, 300, 255, 256, 1024, 1_000_000, (1L << 50) + 3};
        long[] values = new long[base.length * 111];
        for (int i = 0; i < 111; i++) {
            System.arraycopy(base, 0, values, i * base.length, base.length);
        }
        Histogram a = new Histogram(7, 64);
        for (long v : values) {
            a.increment(v);
        }
        Histogram b = new Histogram(7, 64);
        b.recordMany(values);
        assertEquals(a, b);
    }

    @Test
    void recordManyWithCounts() {
        Histogram a = new Histogram(7, 64);
        a.recordMany(new long[] {10, 20, 10}, new long[] {2, 3, 5});
        assertEquals(10, a.totalCount());
        int idx = a.config().valueToIndex(10);
        assertEquals(7, a.bucketCounts()[idx]);
        assertThrows(IllegalArgumentException.class,
                () -> a.recordMany(new long[] {1, 2}, new long[] {1}));
    }

    @Test
    void iterBuckets() {
        Histogram h = new Histogram(3, 6);
        h.increment(0);
        AtomicInteger count = new AtomicInteger();
        h.forEachBucket(b -> count.incrementAndGet());
        assertEquals(h.config().totalBuckets(), count.get());
        List<Bucket> nonzero = h.nonzeroBuckets();
        assertEquals(1, nonzero.size());
        assertEquals(1, nonzero.get(0).count());
    }

    @Test
    void fullU64Range() {
        // Values above Long.MAX_VALUE are handled with unsigned semantics.
        Histogram h = new Histogram(7, 64);
        h.increment(-1L); // u64::MAX
        h.increment(Long.MIN_VALUE); // 2^63
        assertEquals(2, h.totalCount());
        Optional<Bucket> p100 = h.percentile(1.0);
        assertTrue(p100.isPresent());
        assertEquals(-1L, p100.get().end()); // upper bound is u64::MAX
    }
}
