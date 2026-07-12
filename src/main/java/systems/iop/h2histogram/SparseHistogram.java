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

    /** Computes a percentile via the dense representation. */
    public Optional<Bucket> percentile(double percentile) {
        return toDense().percentile(percentile);
    }

    /** Computes percentiles via the dense representation. */
    public List<PercentileResult> percentiles(double... percentiles) {
        return toDense().percentiles(percentiles);
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
