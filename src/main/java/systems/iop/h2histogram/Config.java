package systems.iop.h2histogram;

/**
 * An immutable bucketing configuration.
 *
 * <p>The bucketing strategy is fully determined by two parameters:
 *
 * <ul>
 *   <li>{@code groupingPower} — the number of buckets used to span consecutive
 *       powers of two. It controls the relative error,
 *       {@code 2^-groupingPower}. For example {@code groupingPower=7} gives a
 *       relative error of ~0.78%.
 *   <li>{@code maxValuePower} — the largest representable value is
 *       {@code 2^maxValuePower - 1}.
 * </ul>
 *
 * <p>The layout has two regions: a linear region covering
 * {@code 0 .. 2^(groupingPower+1)} where every bucket has width 1 (exact), and
 * a logarithmic region above the cutoff subdivided into
 * {@code 2^groupingPower} buckets per power of two.
 *
 * <p>This is a faithful port of the {@code Config} type from the Rust
 * <a href="https://github.com/iopsystems/histogram">histogram</a> crate and
 * produces byte-for-byte identical bucketing.
 *
 * <p>Values are unsigned 64-bit integers carried in Java {@code long}s: a
 * negative {@code long} represents a value above {@code Long.MAX_VALUE}, so
 * the full {@code u64} range is supported.
 */
public final class Config {
    private final int groupingPower;
    private final int maxValuePower;
    private final long max; // unsigned
    private final int cutoffPower;
    private final long cutoffValue; // unsigned
    private final int lowerBinCount;
    private final int upperBinDivisions;
    private final int upperBinCount;

    /**
     * Creates and validates a {@code Config}.
     *
     * <p>The constraints match the Rust crate:
     *
     * <ul>
     *   <li>{@code maxValuePower} must be in the range {@code 0..=64}
     *   <li>{@code groupingPower} must be non-negative and less than
     *       {@code maxValuePower}
     * </ul>
     *
     * @throws IllegalArgumentException if the parameters are out of range
     */
    public Config(int groupingPower, int maxValuePower) {
        if (maxValuePower < 0 || maxValuePower > 64) {
            throw new IllegalArgumentException(
                    "max_value_power must be <= 64, got " + maxValuePower);
        }
        if (groupingPower < 0 || groupingPower >= maxValuePower) {
            throw new IllegalArgumentException(
                    "grouping_power (" + groupingPower
                            + ") must be non-negative and less than max_value_power ("
                            + maxValuePower + ")");
        }

        // The cutoff is the point at which the linear divisions and the
        // logarithmic subdivisions have the same width:
        // cutoffPower = groupingPower + 1.
        this.groupingPower = groupingPower;
        this.maxValuePower = maxValuePower;
        this.cutoffPower = groupingPower + 1;
        this.cutoffValue = 1L << cutoffPower;
        this.upperBinDivisions = 1 << groupingPower;
        this.max = maxValuePower == 64 ? -1L : (1L << maxValuePower) - 1;
        this.lowerBinCount = (int) cutoffValue;
        this.upperBinCount = (maxValuePower - cutoffPower) * upperBinDivisions;
    }

    /**
     * Infers a {@code Config} from a known bucket count and
     * {@code maxValuePower}.
     *
     * <p>Rezolus/metriken parquet files store dense histograms as a bare list
     * of bucket counts without recording {@code groupingPower}. Given the
     * number of buckets and the (conventionally fixed) {@code maxValuePower}
     * the grouping power can be recovered uniquely.
     *
     * @throws IllegalArgumentException if no grouping power produces
     *     {@code totalBuckets}
     */
    public static Config fromTotalBuckets(int totalBuckets, int maxValuePower) {
        for (int groupingPower = 0; groupingPower < maxValuePower; groupingPower++) {
            Config candidate;
            try {
                candidate = new Config(groupingPower, maxValuePower);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (candidate.totalBuckets() == totalBuckets) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "no grouping_power with max_value_power=" + maxValuePower
                        + " yields " + totalBuckets + " buckets");
    }

    /** Returns the grouping power used to create this configuration. */
    public int groupingPower() {
        return groupingPower;
    }

    /** Returns the max value power used to create this configuration. */
    public int maxValuePower() {
        return maxValuePower;
    }

    /**
     * Returns the largest value representable by this configuration,
     * i.e. {@code 2^maxValuePower - 1}, as an unsigned {@code long}.
     */
    public long max() {
        return max;
    }

    /** Returns the total number of buckets for this configuration. */
    public int totalBuckets() {
        return lowerBinCount + upperBinCount;
    }

    /**
     * Returns the relative error (as a percentage) of the logarithmic buckets.
     * Linear buckets have width 1 and no error. If the config has no
     * logarithmic buckets the error is zero.
     */
    public double error() {
        if (groupingPower == maxValuePower - 1) {
            return 0.0;
        }
        return 100.0 / (double) (1L << groupingPower);
    }

    /**
     * Returns the bucket index that {@code value} (unsigned) falls into.
     *
     * @throws IllegalArgumentException if the value is greater than the
     *     configured maximum
     */
    public int valueToIndex(long value) {
        if (Long.compareUnsigned(value, cutoffValue) < 0) {
            return (int) value;
        }
        if (Long.compareUnsigned(value, max) > 0) {
            throw new IllegalArgumentException(
                    "value " + Long.toUnsignedString(value)
                            + " is out of range for max " + Long.toUnsignedString(max));
        }

        // power = floor(log2(value)); equivalent to 63 - leading_zeros for u64.
        int power = 63 - Long.numberOfLeadingZeros(value);
        int logBin = power - cutoffPower;
        long offset = (value - (1L << power)) >>> (power - groupingPower);

        return lowerBinCount + logBin * upperBinDivisions + (int) offset;
    }

    /** Returns the inclusive (unsigned) lower bound of the bucket at {@code index}. */
    public long indexToLowerBound(int index) {
        long g = ((long) index) >>> groupingPower;
        long h = ((long) index) - g * (1L << groupingPower);
        if (g < 1) {
            return h;
        }
        return (1L << (groupingPower + g - 1)) + (1L << (g - 1)) * h;
    }

    /** Returns the inclusive (unsigned) upper bound of the bucket at {@code index}. */
    public long indexToUpperBound(int index) {
        if (index == totalBuckets() - 1) {
            return max;
        }
        long g = ((long) index) >>> groupingPower;
        long h = ((long) index) - g * (1L << groupingPower) + 1;
        if (g < 1) {
            return h - 1;
        }
        return (1L << (groupingPower + g - 1)) + (1L << (g - 1)) * h - 1;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Config other)) {
            return false;
        }
        return groupingPower == other.groupingPower && maxValuePower == other.maxValuePower;
    }

    @Override
    public int hashCode() {
        return 31 * groupingPower + maxValuePower;
    }

    @Override
    public String toString() {
        return "Config(grouping_power=" + groupingPower
                + ", max_value_power=" + maxValuePower + ")";
    }
}
