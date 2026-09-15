package systems.iop.h2histogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A dense h2 histogram that stores a counter for every bucket.
 *
 * <p>Values are quantized into buckets according to a {@link Config}
 * determined by {@code groupingPower} and {@code maxValuePower}. This is the
 * Java analogue of the Rust {@code Histogram} type and produces byte-for-byte
 * identical bucketing, so histograms recorded here can be consumed by
 * Rezolus (and vice versa).
 *
 * <p>Values and counts are unsigned 64-bit integers carried in Java
 * {@code long}s, so the full {@code u64} range is supported.
 */
public final class Histogram {
    private final Config config;
    private final long[] buckets;

    /**
     * Creates an empty histogram with the given {@code groupingPower} and
     * {@code maxValuePower}.
     *
     * @throws IllegalArgumentException if the parameters are invalid (see
     *     {@link Config#Config(int, int)})
     */
    public Histogram(int groupingPower, int maxValuePower) {
        this(new Config(groupingPower, maxValuePower));
    }

    /** Creates an empty histogram from an existing {@link Config}. */
    public Histogram(Config config) {
        this.config = config;
        this.buckets = new long[config.totalBuckets()];
    }

    /**
     * Creates a histogram from a full, dense array of bucket counts. The
     * length of {@code buckets} must equal the config's
     * {@link Config#totalBuckets()}.
     *
     * @throws IllegalArgumentException if the parameters are invalid or the
     *     length does not match
     */
    public static Histogram fromBuckets(int groupingPower, int maxValuePower, long[] buckets) {
        Config config = new Config(groupingPower, maxValuePower);
        if (buckets.length != config.totalBuckets()) {
            throw new IllegalArgumentException(
                    "expected " + config.totalBuckets() + " buckets, got " + buckets.length);
        }
        Histogram h = new Histogram(config);
        System.arraycopy(buckets, 0, h.buckets, 0, buckets.length);
        return h;
    }

    /** Returns the bucketing configuration. */
    public Config config() {
        return config;
    }

    /** Returns a copy of the dense bucket counts (one entry per bucket). */
    public long[] bucketCounts() {
        return buckets.clone();
    }

    /** Returns the internal bucket array without copying. */
    long[] bucketsRef() {
        return buckets;
    }

    /** Returns the number of buckets. */
    public int size() {
        return buckets.length;
    }

    /** Returns the total number of observations recorded (unsigned). */
    public long totalCount() {
        long total = 0;
        for (long c : buckets) {
            total += c;
        }
        return total;
    }

    /**
     * Adds one observation of {@code value}.
     *
     * @throws IllegalArgumentException if value is out of range for the histogram
     */
    public void increment(long value) {
        record(value, 1);
    }

    /**
     * Adds {@code count} observations of {@code value}.
     *
     * @throws IllegalArgumentException if value is out of range for the histogram
     */
    public void record(long value, long count) {
        buckets[config.valueToIndex(value)] += count;
    }

    /**
     * Records each value in {@code values} once.
     *
     * @throws IllegalArgumentException (stopping) if any value is out of range
     */
    public void recordMany(long[] values) {
        for (long v : values) {
            record(v, 1);
        }
    }

    /**
     * Records each value with the corresponding weight from {@code counts}.
     *
     * @throws IllegalArgumentException if the arrays differ in length or any
     *     value is out of range
     */
    public void recordMany(long[] values, long[] counts) {
        if (values.length != counts.length) {
            throw new IllegalArgumentException(
                    "values and counts must have the same length ("
                            + values.length + " != " + counts.length + ")");
        }
        for (int i = 0; i < values.length; i++) {
            record(values[i], counts[i]);
        }
    }

    /** Clears all counts while retaining the dense counter array. Not thread-safe. */
    public void reset() {
        Arrays.fill(buckets, 0);
    }

    /**
     * Replaces a compatible destination's counts with this histogram's counts.
     * Both histograms must be exclusively owned or externally synchronized.
     * Self-snapshot is allowed. The destination array is retained.
     */
    public void snapshotInto(Histogram destination) {
        checkCompatible(destination);
        System.arraycopy(buckets, 0, destination.buckets, 0, buckets.length);
    }

    /**
     * Snapshots into a compatible existing destination, then resets this histogram.
     * This is not a concurrent/atomic drain. Self-drain is rejected before mutation.
     */
    public void drainInto(Histogram destination) {
        if (this == destination) {
            throw new IllegalArgumentException("cannot drain into self");
        }
        snapshotInto(destination);
        reset();
    }

    /**
     * Adds counts in place, checking all buckets before mutation. Aliasing is allowed.
     * Throws ArithmeticException on unsigned bucket overflow and IllegalArgumentException
     * on incompatible configuration; either failure leaves this histogram unchanged.
     * Requires exclusive access to both histograms throughout the operation.
     */
    public void checkedAddAssign(Histogram other) {
        checkCompatible(other);
        for (int i = 0; i < buckets.length; i++) {
            U64.checkedAdd(buckets[i], other.buckets[i]);
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] += other.buckets[i];
        }
    }

    /**
     * Returns an independent sum, checking every configuration before adding counts.
     * The nonempty list may contain repeated histogram references. Sources are never
     * mutated and must not be concurrently modified. Unsigned bucket overflow throws
     * ArithmeticException; empty input or incompatible geometry throws IllegalArgumentException.
     */
    public static Histogram checkedSum(List<Histogram> histograms) {
        if (histograms.isEmpty()) {
            throw new IllegalArgumentException("cannot sum an empty list");
        }
        Histogram first = histograms.get(0);
        for (Histogram histogram : histograms) {
            first.checkCompatible(histogram);
        }
        Histogram result = new Histogram(first.config);
        System.arraycopy(first.buckets, 0, result.buckets, 0, first.buckets.length);
        Iterator<Histogram> remaining = histograms.iterator();
        remaining.next();
        while (remaining.hasNext()) {
            Histogram histogram = remaining.next();
            for (int i = 0; i < result.buckets.length; i++) {
                result.buckets[i] = U64.checkedAdd(result.buckets[i], histogram.buckets[i]);
            }
        }
        return result;
    }

    // Bucket iteration ------------------------------------------------------

    private Bucket bucketAt(int index) {
        return new Bucket(buckets[index],
                config.indexToLowerBound(index), config.indexToUpperBound(index));
    }

    /** Calls {@code action} for every bucket in ascending index order. */
    public void forEachBucket(Consumer<Bucket> action) {
        for (int i = 0; i < buckets.length; i++) {
            action.accept(bucketAt(i));
        }
    }

    /** Returns every bucket with a non-zero count, in ascending index order. */
    public List<Bucket> nonzeroBuckets() {
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] != 0) {
                out.add(bucketAt(i));
            }
        }
        return out;
    }

    // Combination -----------------------------------------------------------

    private void checkCompatible(Histogram other) {
        if (!config.equals(other.config)) {
            throw new IllegalArgumentException("histograms have incompatible configurations");
        }
    }

    /**
     * Returns a new histogram that is the element-wise sum of {@code this} and
     * {@code other}. Both histograms must share the same configuration.
     *
     * @throws IllegalArgumentException if the configurations differ
     */
    public Histogram merge(Histogram other) {
        checkCompatible(other);
        Histogram result = new Histogram(config);
        for (int i = 0; i < buckets.length; i++) {
            result.buckets[i] = buckets[i] + other.buckets[i];
        }
        return result;
    }

    /**
     * Returns a new histogram that is the element-wise difference of
     * {@code this} and {@code other}.
     *
     * @throws IllegalArgumentException if any bucket would go negative or the
     *     configurations differ
     */
    public Histogram subtract(Histogram other) {
        checkCompatible(other);
        Histogram result = new Histogram(config);
        for (int i = 0; i < buckets.length; i++) {
            if (Long.compareUnsigned(other.buckets[i], buckets[i]) > 0) {
                throw new IllegalArgumentException(
                        "subtraction would produce a negative bucket count");
            }
            result.buckets[i] = buckets[i] - other.buckets[i];
        }
        return result;
    }

    /**
     * Returns a coarser histogram with a smaller {@code groupingPower}. Every
     * step down approximately halves the number of buckets while doubling the
     * relative error. The new grouping power must be strictly less than the
     * current one.
     *
     * @throws IllegalArgumentException if {@code groupingPower} is not smaller
     *     than the current grouping power
     */
    public Histogram downsample(int groupingPower) {
        if (groupingPower >= config.groupingPower()) {
            throw new IllegalArgumentException(
                    "target grouping_power must be less than the current grouping_power");
        }
        Histogram result = new Histogram(groupingPower, config.maxValuePower());
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] != 0) {
                result.record(config.indexToLowerBound(i), buckets[i]);
            }
        }
        return result;
    }

    // Quantiles / percentiles -------------------------------------------------

    /**
     * Returns the bucket at a single percentile in {@code [0.0, 1.0]}, or
     * empty if the histogram has no observations. The percentile uses the same
     * fractional convention as the Rust crate: 0.5 is the median.
     *
     * @throws IllegalArgumentException if the percentile is out of range
     * @throws ArithmeticException if the total exceeds unsigned 64-bit range
     */
    public Optional<Bucket> percentile(double percentile) {
        U64.validatePercentile(percentile);
        long total = U64.checkedTotal(buckets);
        return total == 0 ? Optional.empty() : Optional.of(percentileBucket(percentile, total));
    }

    private Bucket percentileBucket(double percentile, long total) {
        long target = U64.ceilCount(percentile, total);
        long running = 0;
        for (int i = 0; i < buckets.length; i++) {
            running += buckets[i];
            if (Long.compareUnsigned(running, target) >= 0) {
                return bucketAt(i);
            }
        }
        throw new IllegalStateException("count changed during query");
    }

    /**
     * Writes buckets in request order into caller storage and returns the number written.
     * Empty histograms return zero and leave output untouched; unused output entries
     * are untouched. Validates all requests and capacity before writing. Bucket objects
     * are still created. Each request scans counts; no batch request/result containers
     * are constructed. Unsigned total overflow throws ArithmeticException.
     */
    public int percentilesInto(double[] percentiles, Bucket[] output) {
        U64.validateOutput(percentiles, output);
        if (percentiles.length == 0) {
            return 0;
        }
        long total = U64.checkedTotal(buckets);
        if (total == 0) {
            return 0;
        }
        for (int i = 0; i < percentiles.length; i++) {
            output[i] = percentileBucket(percentiles[i], total);
        }
        return percentiles.length;
    }

    /**
     * Returns a {@link PercentileResult} for each requested percentile, in the
     * same order as the input. Each percentile must be in {@code [0.0, 1.0]}.
     * Returns an empty list if the histogram has no observations. This mirrors
     * the algorithm used by the Rust crate.
     *
     * @throws IllegalArgumentException if any percentile is out of range
     * @throws ArithmeticException if the total exceeds unsigned 64-bit range
     */
    public List<PercentileResult> percentiles(double... percentiles) {
        for (double p : percentiles) {
            if (!(p >= 0.0 && p <= 1.0)) {
                throw new IllegalArgumentException(
                        "percentiles must be in the range [0.0, 1.0], got " + p);
            }
        }

        if (percentiles.length == 0) {
            return List.of();
        }
        long total = U64.checkedTotal(buckets);
        if (total == 0) {
            return List.of();
        }

        // Deduplicate and sort while remembering the original order for output.
        double[] sortedUnique = Arrays.stream(percentiles).distinct().sorted().toArray();

        Map<Double, Bucket> resultsByP = new HashMap<>(sortedUnique.length * 2);
        int bucketIdx = 0;
        long partialSum = buckets[0];

        for (double p : sortedUnique) {
            long target = U64.ceilCount(p, total);
            while (Long.compareUnsigned(partialSum, target) < 0
                    && bucketIdx < buckets.length - 1) {
                bucketIdx++;
                partialSum += buckets[bucketIdx];
            }
            resultsByP.put(p, bucketAt(bucketIdx));
        }

        List<PercentileResult> out = new ArrayList<>(percentiles.length);
        for (double p : percentiles) {
            out.add(new PercentileResult(p, resultsByP.get(p)));
        }
        return out;
    }

    /** Alias for {@link #percentile(double)} (the crate uses "quantile"). */
    public Optional<Bucket> quantile(double quantile) {
        return percentile(quantile);
    }

    // Conversions -------------------------------------------------------------

    /** Converts to the sparse (columnar) representation. */
    public SparseHistogram toSparse() {
        return SparseHistogram.fromHistogram(this);
    }

    /** Converts to a read-only cumulative histogram for fast quantiles. */
    public CumulativeHistogram toCumulative() {
        return CumulativeHistogram.fromHistogram(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Histogram other)) {
            return false;
        }
        return config.equals(other.config) && Arrays.equals(buckets, other.buckets);
    }

    @Override
    public int hashCode() {
        return 31 * config.hashCode() + Arrays.hashCode(buckets);
    }

    @Override
    public String toString() {
        return "Histogram(grouping_power=" + config.groupingPower()
                + ", max_value_power=" + config.maxValuePower()
                + ", total_count=" + Long.toUnsignedString(totalCount()) + ")";
    }
}
