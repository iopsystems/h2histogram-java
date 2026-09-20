package systems.iop.h2histogram;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A histogram whose buckets are atomic counters, so several threads can record
 * into one shared instance without external locking.
 *
 * <p>This is the Java analogue of the Rust {@code AtomicHistogram}. Unlike
 * {@link Histogram} it cannot report percentiles directly. Take a non-atomic
 * snapshot with {@link #load()} and query that.
 *
 * <h2>Concurrency contract</h2>
 *
 * <ul>
 *   <li>Every method is safe to call from any thread at any time.
 *   <li>{@link #increment(long)} and {@link #record(long, long)} are lock-free:
 *       one atomic add on one counter. No total, minimum or maximum is cached.
 *   <li>{@link #load()} and {@link #loadInto(Histogram)} read each bucket
 *       individually. The result is not one instantaneous histogram-wide
 *       snapshot: a write concurrent with a load may or may not be included.
 * </ul>
 *
 * <p>There is no instantaneous boundary across buckets. An exact interval
 * boundary requires the caller to pause or hand off writers.
 *
 * <p>Adjacent buckets share cache lines, so threads recording into
 * neighbouring hot buckets contend through false sharing. Where each writer
 * can own its histogram, one plain {@link Histogram} per thread merged later
 * is faster; use this type when writers must share an instance.
 *
 * <p>Values and counts are unsigned 64-bit integers carried in {@code long}s.
 * Counts wrap modulo 2^64. Instances use identity equality: compare snapshots,
 * not live histograms.
 */
public final class AtomicHistogram {
    private final Config config;
    private final AtomicLongArray buckets;

    /**
     * Creates an empty atomic histogram.
     *
     * @throws IllegalArgumentException if the parameters are invalid (see
     *     {@link Config#Config(int, int)})
     */
    public AtomicHistogram(int groupingPower, int maxValuePower) {
        this(new Config(groupingPower, maxValuePower));
    }

    /** Creates an empty atomic histogram from an existing {@link Config}. */
    public AtomicHistogram(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.buckets = new AtomicLongArray(config.totalBuckets());
    }

    /** Returns the bucketing configuration. */
    public Config config() {
        return config;
    }

    /**
     * Adds one observation of {@code value}.
     *
     * @throws IllegalArgumentException if value is out of range; no counter changes
     */
    public void increment(long value) {
        record(value, 1);
    }

    /**
     * Adds {@code count} observations of {@code value}.
     *
     * @throws IllegalArgumentException if value is out of range; no counter changes
     */
    public void record(long value, long count) {
        // valueToIndex validates before returning, so a bad value never reaches
        // the counters.
        buckets.getAndAdd(config.valueToIndex(value), count);
    }

    /** Copies the current bucket values into a new {@link Histogram}. */
    public Histogram load() {
        Histogram snapshot = new Histogram(config);
        loadInto(snapshot);
        return snapshot;
    }

    /**
     * Overwrites {@code destination} with the current bucket values, reusing
     * its storage. Every destination bucket is replaced, including with zero.
     * The caller must own {@code destination} exclusively during the call.
     *
     * @throws IllegalArgumentException if the configurations differ; neither
     *     histogram is changed
     */
    public void loadInto(Histogram destination) {
        long[] out = checkedDestination(destination);
        for (int i = 0; i < out.length; i++) {
            out[i] = buckets.getAcquire(i);
        }
    }

    // Validates before any counter is read or written, so a mismatch leaves
    // both histograms untouched.
    private long[] checkedDestination(Histogram destination) {
        if (!config.equals(destination.config())) {
            throw new IllegalArgumentException(
                    "destination histogram has an incompatible configuration");
        }
        return destination.bucketsRef();
    }

    @Override
    public String toString() {
        return "AtomicHistogram(grouping_power=" + config.groupingPower()
                + ", max_value_power=" + config.maxValuePower() + ")";
    }
}
