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
        return fromDouble(target);
    }
}
