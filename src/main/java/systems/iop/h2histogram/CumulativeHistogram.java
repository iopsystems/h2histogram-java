package systems.iop.h2histogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A read-only histogram with cumulative counts for fast quantile queries.
 *
 * <p>It corresponds to {@code CumulativeROHistogram} in the Rust histogram
 * crate. It is a variant of {@link SparseHistogram} that stores only non-zero
 * buckets in columnar form, but with cumulative counts: {@code count[i]} is
 * the running prefix sum of individual bucket counts, so the last element
 * equals the total observation count.
 *
 * <p>Because the counts are cumulative, percentile queries are answered with
 * a binary search (O(log n) in the number of non-zero buckets) rather than a
 * linear scan. The histogram is read-only. A midpoint-estimated mean is
 * computed once at construction.
 */
public final class CumulativeHistogram {
    private final Config config;
    private final int[] index;
    private final long[] count; // cumulative (prefix-sum) counts
    private final double mean;
    private final boolean hasMean;

    private CumulativeHistogram(Config config, int[] index, long[] count) {
        this.config = config;
        this.index = index;
        this.count = count;
        double meanValue = 0.0;
        boolean meanPresent = false;
        if (count.length != 0 && count[count.length - 1] != 0) {
            long total = count[count.length - 1];
            double weighted = 0.0;
            for (int i = 0; i < index.length; i++) {
                long individual = individualCount(i);
                double midpoint = (U64.toDouble(config.indexToLowerBound(index[i]))
                        + U64.toDouble(config.indexToUpperBound(index[i]))) / 2.0;
                weighted += midpoint * U64.toDouble(individual);
            }
            meanValue = weighted / U64.toDouble(total);
            meanPresent = true;
        }
        this.mean = meanValue;
        this.hasMean = meanPresent;
    }

    /**
     * Creates a {@code CumulativeHistogram} from raw parts. {@code count} must
     * be cumulative (prefix sums).
     *
     * @throws IllegalArgumentException if the lengths differ, an index is out
     *     of range, the indices are not strictly ascending, the counts are not
     *     non-decreasing, or any count is zero
     */
    public static CumulativeHistogram fromParts(Config config, int[] index, long[] count) {
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
        long prevCount = 0;
        for (long c : count) {
            if (c == 0) {
                throw new IllegalArgumentException("cumulative counts must be non-zero");
            }
            if (Long.compareUnsigned(c, prevCount) < 0) {
                throw new IllegalArgumentException("cumulative counts must be non-decreasing");
            }
            prevCount = c;
        }
        return new CumulativeHistogram(config, index.clone(), count.clone());
    }

    /** Builds a {@code CumulativeHistogram} from a dense {@link Histogram}. */
    public static CumulativeHistogram fromHistogram(Histogram histogram) {
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
        long running = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] != 0) {
                running += buckets[i];
                index[k] = i;
                count[k] = running;
                k++;
            }
        }
        return new CumulativeHistogram(histogram.config(), index, count);
    }

    /** Builds a {@code CumulativeHistogram} from a {@link SparseHistogram}. */
    public static CumulativeHistogram fromSparse(SparseHistogram sparse) {
        int[] index = sparse.indexRef().clone();
        long[] sparseCount = sparse.countRef();
        long[] cumulative = new long[sparseCount.length];
        long running = 0;
        for (int i = 0; i < sparseCount.length; i++) {
            running += sparseCount[i];
            cumulative[i] = running;
        }
        return new CumulativeHistogram(sparse.config(), index, cumulative);
    }

    private long individualCount(int position) {
        if (position == 0) {
            return count[0];
        }
        return count[position] - count[position - 1];
    }

    /** Returns the bucketing configuration. */
    public Config config() {
        return config;
    }

    /** Returns a copy of the non-zero bucket indices, ascending. */
    public int[] index() {
        return index.clone();
    }

    /** Returns a copy of the cumulative (prefix-sum) counts aligned with {@link #index()}. */
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
        if (count.length == 0) {
            return 0;
        }
        return count[count.length - 1];
    }

    /**
     * Returns the midpoint-estimated mean of all observations, or empty if the
     * histogram is empty. It is computed once at construction.
     */
    public OptionalDouble mean() {
        return hasMean ? OptionalDouble.of(mean) : OptionalDouble.empty();
    }

    /** Returns the first position where the cumulative count is {@code >= target}. */
    private int findQuantilePosition(long target) {
        int lo = 0;
        int hi = count.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(count[mid], target) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo >= count.length) {
            return count.length - 1;
        }
        return lo;
    }

    private Bucket bucketAt(int position) {
        return new Bucket(individualCount(position),
                config.indexToLowerBound(index[position]),
                config.indexToUpperBound(index[position]));
    }

    /**
     * Returns the bucket at {@code percentile} in {@code [0.0, 1.0]}, or empty
     * if the histogram has no observations. The returned bucket carries the
     * individual (non-cumulative) count.
     *
     * @throws IllegalArgumentException if the percentile is out of range
     */
    public Optional<Bucket> percentile(double percentile) {
        List<PercentileResult> results = percentiles(percentile);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.get(0).bucket());
    }

    /**
     * Returns a {@link PercentileResult} per requested percentile, in input
     * order. Each percentile must be in {@code [0.0, 1.0]}. Returns an empty
     * list if the histogram has no observations.
     *
     * @throws IllegalArgumentException if any percentile is out of range
     */
    public List<PercentileResult> percentiles(double... percentiles) {
        for (double p : percentiles) {
            if (!(p >= 0.0 && p <= 1.0)) {
                throw new IllegalArgumentException(
                        "percentiles must be in the range [0.0, 1.0], got " + p);
            }
        }
        if (count.length == 0 || count[count.length - 1] == 0) {
            return List.of();
        }
        long total = count[count.length - 1];

        List<PercentileResult> out = new ArrayList<>(percentiles.length);
        for (double p : percentiles) {
            long target = U64.ceilCount(p, total);
            int pos = findQuantilePosition(target);
            out.add(new PercentileResult(p, bucketAt(pos)));
        }
        return out;
    }

    /** Alias for {@link #percentile(double)}. */
    public Optional<Bucket> quantile(double quantile) {
        return percentile(quantile);
    }

    /**
     * Returns the quantile span for the {@code position}-th stored bucket, or
     * empty if the histogram is empty or the position is out of range. The
     * result's {@code lowerQuantile} is the fraction of observations strictly
     * before this bucket and {@code upperQuantile} the fraction at or before
     * it, both in {@code [0.0, 1.0]}.
     */
    public Optional<BucketWithQuantiles> bucketQuantileRange(int position) {
        if (position < 0 || position >= count.length || count[count.length - 1] == 0) {
            return Optional.empty();
        }
        double total = U64.toDouble(count[count.length - 1]);
        double lower = position == 0 ? 0.0 : U64.toDouble(count[position - 1]) / total;
        double upper = U64.toDouble(count[position]) / total;
        return Optional.of(new BucketWithQuantiles(bucketAt(position), lower, upper));
    }

    /** Returns each stored bucket with its individual count. */
    public List<Bucket> buckets() {
        List<Bucket> out = new ArrayList<>(index.length);
        for (int i = 0; i < index.length; i++) {
            out.add(bucketAt(i));
        }
        return out;
    }

    /** Returns each non-zero bucket with its (lowerQuantile, upperQuantile) span. */
    public List<BucketWithQuantiles> bucketsWithQuantiles() {
        List<BucketWithQuantiles> out = new ArrayList<>(index.length);
        double total = count.length == 0 ? 0.0 : U64.toDouble(count[count.length - 1]);
        for (int i = 0; i < index.length; i++) {
            double lower = i == 0 ? 0.0 : U64.toDouble(count[i - 1]) / total;
            double upper = U64.toDouble(count[i]) / total;
            out.add(new BucketWithQuantiles(bucketAt(i), lower, upper));
        }
        return out;
    }

    /** Reconstructs a dense {@link Histogram}. */
    public Histogram toDense() {
        Histogram h = new Histogram(config);
        long[] buckets = h.bucketsRef();
        for (int i = 0; i < index.length; i++) {
            buckets[index[i]] = individualCount(i);
        }
        return h;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CumulativeHistogram other)) {
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
        return "CumulativeHistogram(grouping_power=" + config.groupingPower()
                + ", max_value_power=" + config.maxValuePower()
                + ", nonzero_buckets=" + index.length
                + ", total_count=" + Long.toUnsignedString(totalCount()) + ")";
    }
}
