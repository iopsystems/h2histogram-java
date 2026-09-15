package systems.iop.h2histogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A histogram stored as {@code (index, count)} pairs for non-zero buckets.
 *
 * <p>Only non-zero buckets are stored, as two parallel arrays {@code index}
 * and {@code count} in ascending index order. This is the form Rezolus uses
 * for its {@code :bucket_indices} / {@code :bucket_counts} parquet columns.
 */
public final class SparseHistogram {
    private final Config config;
    private final int[] index;
    private final long[] count;

    private SparseHistogram(Config config, int[] index, long[] count) {
        this.config = config;
        this.index = index;
        this.count = count;
    }

    /** Builds a sparse histogram from a dense {@link Histogram}. */
    public static SparseHistogram fromHistogram(Histogram histogram) {
        long[] buckets = histogram.bucketsRef();
        int nonzero = 0;
        for (long c : buckets) {
            if (c != 0) {
                nonzero++;
            }
        }
        int[] index = new int[nonzero];
        long[] count = new long[nonzero];
        int k = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] != 0) {
                index[k] = i;
                count[k] = buckets[i];
                k++;
            }
        }
        return new SparseHistogram(histogram.config(), index, count);
    }

    /**
     * Creates a sparse histogram from raw parts, validating invariants.
     *
     * @throws IllegalArgumentException if the lengths differ, an index is out
     *     of range, or the indices are not strictly ascending
     */
    public static SparseHistogram fromParts(Config config, int[] index, long[] count) {
        if (index.length != count.length) {
            throw new IllegalArgumentException(
                    "index and count must have the same length ("
                            + index.length + " != " + count.length + ")");
        }
        int total = config.totalBuckets();
        int prev = -1;
        for (int i : index) {
            if (i < 0 || i >= total) {
                throw new IllegalArgumentException("index " + i + " out of range for config");
            }
            if (i <= prev) {
                throw new IllegalArgumentException("indices must be strictly ascending");
            }
            prev = i;
        }
        return new SparseHistogram(config, index.clone(), count.clone());
    }

    /** Returns the bucketing configuration. */
    public Config config() {
        return config;
    }

    /** Returns a copy of the non-zero bucket indices, ascending. */
    public int[] index() {
        return index.clone();
    }

    /** Returns a copy of the counts corresponding to {@link #index()}. */
    public long[] count() {
        return count.clone();
    }

    /** Returns the number of stored (non-zero) buckets. */
    public int size() {
        return index.length;
    }

    /** Returns whether the histogram has no stored buckets. */
    public boolean isEmpty() {
        return index.length == 0;
    }

    /** Returns the total number of observations (unsigned). */
    public long totalCount() {
        long total = 0;
        for (long c : count) {
            total += c;
        }
        return total;
    }

    /** Returns each stored bucket with its individual count. */
    public List<Bucket> buckets() {
        List<Bucket> out = new ArrayList<>(index.length);
        for (int k = 0; k < index.length; k++) {
            out.add(new Bucket(count[k],
                    config.indexToLowerBound(index[k]), config.indexToUpperBound(index[k])));
        }
        return out;
    }

    /** Converts to a dense {@link Histogram}. */
    public Histogram toDense() {
        Histogram h = new Histogram(config);
        long[] buckets = h.bucketsRef();
        for (int k = 0; k < index.length; k++) {
            buckets[index[k]] = count[k];
        }
        return h;
    }

    /** Converts to a read-only {@link CumulativeHistogram}. */
    public CumulativeHistogram toCumulative() {
        return CumulativeHistogram.fromSparse(this);
    }

    /**
     * Queries stored counts directly, without constructing a dense histogram.
     * @throws IllegalArgumentException if the percentile is out of range
     * @throws ArithmeticException if the total exceeds unsigned 64-bit range
     */
    public Optional<Bucket> percentile(double percentile) {
        U64.validatePercentile(percentile);
        long total = U64.checkedTotal(count);
        return total == 0 ? Optional.empty() : Optional.of(percentileBucket(percentile, total));
    }

    private Bucket percentileBucket(double percentile, long total) {
        long target = U64.ceilCount(percentile, total);
        long running = 0;
        for (int i = 0; i < count.length; i++) {
            running += count[i];
            if (Long.compareUnsigned(running, target) >= 0) {
                return new Bucket(count[i], config.indexToLowerBound(index[i]),
                        config.indexToUpperBound(index[i]));
            }
        }
        throw new IllegalStateException("invalid total");
    }

    /**
     * Computes percentiles directly on stored counts, preserving request order.
     * @throws IllegalArgumentException if any percentile is out of range
     * @throws ArithmeticException if the total exceeds unsigned 64-bit range
     */
    public List<PercentileResult> percentiles(double... percentiles) {
        for (double p : percentiles) {
            U64.validatePercentile(p);
        }
        if (percentiles.length == 0) {
            return List.of();
        }
        long total = U64.checkedTotal(count);
        if (total == 0) {
            return List.of();
        }
        List<PercentileResult> output = new ArrayList<>(percentiles.length);
        for (double p : percentiles) {
            output.add(new PercentileResult(p, percentileBucket(p, total)));
        }
        return output;
    }

    /**
     * Writes buckets in request order, retaining caller storage. Returns zero without
     * writing for an empty histogram; unused output entries are untouched. Validates
     * all requests/capacity first. Each request scans stored counts; Bucket objects
     * still allocate. Unsigned total overflow throws ArithmeticException.
     */
    public int percentilesInto(double[] percentiles, Bucket[] output) {
        U64.validateOutput(percentiles, output);
        if (percentiles.length == 0) {
            return 0;
        }
        long total = U64.checkedTotal(count);
        if (total == 0) {
            return 0;
        }
        for (int i = 0; i < percentiles.length; i++) {
            output[i] = percentileBucket(percentiles[i], total);
        }
        return percentiles.length;
    }

    /**
     * Merges sorted sparse arrays into an independent result. Configurations must
     * match; unsigned bucket overflow throws ArithmeticException. No dense storage
     * is constructed. Zero entries accepted by fromParts are omitted from output.
     */
    public SparseHistogram merge(SparseHistogram other) {
        if (!config.equals(other.config)) {
            throw new IllegalArgumentException("histograms have incompatible configurations");
        }
        int[] indices = new int[Math.addExact(index.length, other.index.length)];
        long[] counts = new long[indices.length];
        int a = 0, b = 0, size = 0;
        while (a < index.length || b < other.index.length) {
            int next;
            long value;
            if (b == other.index.length || (a < index.length && index[a] < other.index[b])) {
                next = index[a];
                value = count[a++];
            } else if (a == index.length || other.index[b] < index[a]) {
                next = other.index[b];
                value = other.count[b++];
            } else {
                next = index[a];
                value = U64.checkedAdd(count[a++], other.count[b++]);
            }
            if (value != 0) {
                indices[size] = next;
                counts[size++] = value;
            }
        }
        return new SparseHistogram(config, Arrays.copyOf(indices, size), Arrays.copyOf(counts, size));
    }

    /**
     * Maps sorted entries to a strictly coarser configuration and coalesces neighbors.
     * Unsigned bucket overflow throws ArithmeticException. No dense storage is used.
     */
    public SparseHistogram downsample(int groupingPower) {
        if (groupingPower >= config.groupingPower()) {
            throw new IllegalArgumentException("target grouping_power must be less than current");
        }
        Config target = new Config(groupingPower, config.maxValuePower());
        int[] indices = new int[index.length];
        long[] counts = new long[count.length];
        int size = 0;
        for (int i = 0; i < index.length; i++) {
            if (count[i] == 0) {
                continue;
            }
            int mapped = target.valueToIndex(config.indexToLowerBound(index[i]));
            if (size != 0 && indices[size - 1] == mapped) {
                counts[size - 1] = U64.checkedAdd(counts[size - 1], count[i]);
            } else {
                indices[size] = mapped;
                counts[size++] = count[i];
            }
        }
        return new SparseHistogram(target, Arrays.copyOf(indices, size), Arrays.copyOf(counts, size));
    }

    int[] indexRef() {
        return index;
    }

    long[] countRef() {
        return count;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SparseHistogram other)) {
            return false;
        }
        return config.equals(other.config)
                && Arrays.equals(index, other.index)
                && Arrays.equals(count, other.count);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * config.hashCode() + Arrays.hashCode(index)) + Arrays.hashCode(count);
    }

    @Override
    public String toString() {
        return "SparseHistogram(grouping_power=" + config.groupingPower()
                + ", max_value_power=" + config.maxValuePower()
                + ", nonzero_buckets=" + index.length + ")";
    }
}
