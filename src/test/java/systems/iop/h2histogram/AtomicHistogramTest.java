package systems.iop.h2histogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AtomicHistogramTest {

    @Test
    void constructorsAndConfig() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        assertEquals(new Config(7, 64), a.config());

        Config c = new Config(3, 32);
        assertEquals(c, new AtomicHistogram(c).config());

        assertThrows(IllegalArgumentException.class, () -> new AtomicHistogram(7, 65));
        assertThrows(IllegalArgumentException.class, () -> new AtomicHistogram(64, 64));
        assertThrows(IllegalArgumentException.class, () -> new AtomicHistogram(10, 5));
        assertThrows(NullPointerException.class, () -> new AtomicHistogram(null));
    }

    @Test
    void loadMatchesPlainHistogram() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        Histogram expected = new Histogram(7, 64);

        // Linear region, the cutoff, logarithmic region, and values above
        // Long.MAX_VALUE (negative longs are large unsigned values).
        long[] values = {0, 1, 2, 255, 256, 257, 1000, 1L << 40, (1L << 40) + 1,
            Long.MAX_VALUE, Long.MIN_VALUE, -1L};
        for (int i = 0; i < values.length; i++) {
            long count = i + 1;
            a.record(values[i], count);
            expected.record(values[i], count);
        }
        for (long v = 0; v < 600; v++) {
            a.increment(v);
            expected.increment(v);
        }

        assertEquals(expected, a.load());
    }

    @Test
    void outOfRangeThrowsAndChangesNothing() {
        AtomicHistogram a = new AtomicHistogram(7, 10); // max value is 1023
        a.increment(1023);
        assertThrows(IllegalArgumentException.class, () -> a.increment(1024));
        assertThrows(IllegalArgumentException.class, () -> a.record(-1L, 5));

        Histogram expected = new Histogram(7, 10);
        expected.increment(1023);
        assertEquals(expected, a.load());
    }

    @Test
    void loadLeavesCountersInPlace() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(42, 3);
        Histogram first = a.load();
        Histogram second = a.load();
        assertEquals(first, second);
        assertEquals(3, second.totalCount());
    }

    @Test
    void loadIntoOverwritesEveryDestinationBucket() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);

        // The destination holds a count in a bucket that is zero in the source.
        Histogram destination = new Histogram(7, 64);
        destination.record(5000, 9);
        a.loadInto(destination);

        Histogram expected = new Histogram(7, 64);
        expected.record(100, 4);
        assertEquals(expected, destination);
    }

    @Test
    void loadIntoRejectsMismatchedConfigAndChangesNothing() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        Histogram destination = new Histogram(3, 64);
        destination.record(7, 2);
        Histogram before = Histogram.fromBuckets(3, 64, destination.bucketCounts());

        assertThrows(IllegalArgumentException.class, () -> a.loadInto(destination));
        assertEquals(before, destination);
        assertEquals(4, a.load().totalCount());
        assertThrows(NullPointerException.class, () -> a.loadInto(null));
    }

    @Test
    void countsWrapModulo2To64() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(5, -1L); // 2^64 - 1
        a.record(5, 2);
        assertEquals(1, a.load().bucketCounts()[a.config().valueToIndex(5)]);
    }

    @Test
    void drainReturnsCountsAndResetsToZero() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        a.record(1L << 40, 6);

        Histogram expected = new Histogram(7, 64);
        expected.record(100, 4);
        expected.record(1L << 40, 6);

        assertEquals(expected, a.drain());
        assertEquals(new Histogram(7, 64), a.drain());
        assertEquals(new Histogram(7, 64), a.load());
    }

    @Test
    void loadDoesNotConsumeWhatDrainReturns() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(42, 3);
        a.load();
        assertEquals(3, a.drain().totalCount());
    }

    @Test
    void drainIntoOverwritesEveryDestinationBucket() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        Histogram destination = new Histogram(7, 64);
        destination.record(5000, 9);

        a.drainInto(destination);

        Histogram expected = new Histogram(7, 64);
        expected.record(100, 4);
        assertEquals(expected, destination);
        assertEquals(0, a.load().totalCount());
    }

    @Test
    void drainIntoRejectsMismatchedConfigAndChangesNothing() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        Histogram destination = new Histogram(3, 64);
        destination.record(7, 2);
        Histogram before = Histogram.fromBuckets(3, 64, destination.bucketCounts());

        assertThrows(IllegalArgumentException.class, () -> a.drainInto(destination));
        assertEquals(before, destination);
        // The counters were not cleared by the failed drain.
        assertEquals(4, a.load().totalCount());
        assertThrows(NullPointerException.class, () -> a.drainInto(null));
    }

    @Test
    void toStringNamesTheConfiguration() {
        assertEquals("AtomicHistogram(grouping_power=7, max_value_power=64)",
                new AtomicHistogram(7, 64).toString());
    }

    // The two tests below are stress tests. A failure proves a bug; a pass
    // only fails to find one. They cannot prove the absence of a race.

    private static final int WRITERS = 8;
    private static final int PER_WRITER = 200_000;

    /** Half the values hit 16 hot buckets, half spread over the whole range. */
    private static long[][] writerInputs() {
        long[][] inputs = new long[WRITERS][PER_WRITER];
        for (int t = 0; t < WRITERS; t++) {
            Random rng = new Random(1000 + t);
            for (int i = 0; i < PER_WRITER; i++) {
                inputs[t][i] = (i & 1) == 0
                        ? rng.nextInt(16)
                        : rng.nextLong() >>> rng.nextInt(64);
            }
        }
        return inputs;
    }

    private static Histogram expectedFrom(long[][] inputs) {
        Histogram expected = new Histogram(7, 64);
        for (long[] perThread : inputs) {
            for (long v : perThread) {
                expected.increment(v);
            }
        }
        return expected;
    }

    /** Runs {@code body(threadIndex)} on {@code threads} threads released together. */
    private static void runTogether(int threads, IntConsumer body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int index = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS); // rethrows any failure from the thread
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void concurrentWritersAreAllCounted() throws Exception {
        long[][] inputs = writerInputs();
        AtomicHistogram a = new AtomicHistogram(7, 64);

        runTogether(WRITERS, t -> {
            for (long v : inputs[t]) {
                a.increment(v);
            }
        });

        assertEquals(expectedFrom(inputs), a.load());
    }

    @Test
    @Timeout(30)
    void everyCountIsReturnedByExactlyOneDrain() throws Exception {
        long[][] inputs = writerInputs();
        AtomicHistogram a = new AtomicHistogram(7, 64);
        AtomicBoolean writersDone = new AtomicBoolean(false);
        AtomicLong drains = new AtomicLong();
        Histogram[] accumulated = {new Histogram(7, 64)};

        Thread drainer = new Thread(() -> {
            Histogram scratch = new Histogram(7, 64);
            while (!writersDone.get()) {
                a.drainInto(scratch);
                accumulated[0] = accumulated[0].merge(scratch);
                drains.incrementAndGet();
            }
        });
        drainer.start();

        runTogether(WRITERS, t -> {
            for (long v : inputs[t]) {
                a.increment(v);
            }
        });
        writersDone.set(true);
        drainer.join(TimeUnit.SECONDS.toMillis(20));

        Histogram total = accumulated[0].merge(a.drain());
        assertTrue(drains.get() > 0, "the drainer never ran concurrently with writers");
        assertEquals(expectedFrom(inputs), total);
        assertEquals(new Histogram(7, 64), a.load());
    }
}
