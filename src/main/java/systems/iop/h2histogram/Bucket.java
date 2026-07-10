package systems.iop.h2histogram;

/**
 * A single histogram bucket: a count and an inclusive value range.
 *
 * <p>All three components are unsigned 64-bit values carried in Java
 * {@code long}s; use {@link Long#toUnsignedString} and friends when a bound
 * may exceed {@code Long.MAX_VALUE}.
 *
 * @param count the number of observations in the bucket (unsigned)
 * @param start the inclusive lower bound of the bucket's value range (unsigned)
 * @param end the inclusive upper bound of the bucket's value range (unsigned)
 */
public record Bucket(long count, long start, long end) {
    /**
     * Returns the arithmetic midpoint of the bucket range. It is a reasonable
     * point estimate for values that fell into this bucket.
     */
    public double midpoint() {
        return (U64.toDouble(start) + U64.toDouble(end)) / 2.0;
    }

    /** Returns the number of distinct integer values the bucket covers (unsigned). */
    public long width() {
        return end - start + 1;
    }

    @Override
    public String toString() {
        return "Bucket(count=" + Long.toUnsignedString(count)
                + ", range=[" + Long.toUnsignedString(start)
                + ", " + Long.toUnsignedString(end) + "])";
    }
}
