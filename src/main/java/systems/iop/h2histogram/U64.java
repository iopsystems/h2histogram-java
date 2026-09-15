package systems.iop.h2histogram;

/**
 * Helpers for treating Java {@code long} values as unsigned 64-bit integers
 * ({@code u64}).
 *
 * <p>All values and counts in this library are {@code u64}, stored in Java
 * {@code long}s with unsigned semantics ({@link Long#compareUnsigned},
 * {@link Long#toUnsignedString}, ...). This gives the full {@code u64} value
 * range, exactly like the Rust crate.
 */
final class U64 {
    private U64() {}

    private static final double TWO_POW_63 = 9.223372036854775808E18;

    /** Adds unsigned counts without allowing a wrapped result. */
    static long checkedAdd(long a, long b) {
        long result = a + b;
        if (Long.compareUnsigned(result, a) < 0) {
            throw new ArithmeticException("unsigned count overflow");
        }
        return result;
    }

    static long checkedTotal(long[] counts) {
        long total = 0;
        for (long count : counts) {
            total = checkedAdd(total, count);
        }
        return total;
    }

    static void validatePercentile(double p) {
        if (!(p >= 0.0 && p <= 1.0)) {
            throw new IllegalArgumentException("percentiles must be in the range [0.0, 1.0], got " + p);
        }
    }

    static void validateOutput(double[] percentiles, Bucket[] output) {
        if (output.length < percentiles.length) {
            throw new IllegalArgumentException("output must have room for every percentile");
        }
        for (double p : percentiles) {
            validatePercentile(p);
        }
    }

    /** Converts an unsigned 64-bit value to the nearest {@code double}. */
    static double toDouble(long value) {
        if (value >= 0) {
            return value;
        }
        return (double) (value >>> 1) * 2.0 + (value & 1L);
    }

    /** Converts a non-negative {@code double} to an unsigned 64-bit value. */
    static long fromDouble(double value) {
        if (value < TWO_POW_63) {
            return (long) value;
        }
        return ((long) (value - TWO_POW_63)) + Long.MIN_VALUE;
    }

    /**
     * Computes {@code max(1, ceil(p * total))} with {@code total} unsigned,
     * matching the Rust crate's {@code max(1, (q * total).ceil())}.
     */
    static long ceilCount(double p, long total) {
        double target = Math.ceil(p * toDouble(total));
        if (target < 1.0) {
            return 1;
        }
        long count = fromDouble(target);
        return Long.compareUnsigned(count, total) > 0 ? total : count;
    }
}
