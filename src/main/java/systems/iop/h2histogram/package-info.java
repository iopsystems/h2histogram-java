/**
 * A pure-Java implementation of the iopsystems h2 histogram.
 *
 * <p>It produces histograms with byte-for-byte identical bucketing to the Rust
 * <a href="https://github.com/iopsystems/histogram">histogram</a> crate, so
 * histograms recorded here can be consumed by (and interoperate with) Rezolus
 * and the Python, Go, and JavaScript implementations.
 *
 * <p>The bucketing strategy is fully determined by two parameters:
 *
 * <ul>
 *   <li>{@code groupingPower}: the number of buckets used to span consecutive
 *       powers of two. It controls the relative error,
 *       {@code 2^-groupingPower}. For example {@code groupingPower=7} gives a
 *       relative error of ~0.78%.
 *   <li>{@code maxValuePower}: the largest representable value is
 *       {@code 2^maxValuePower - 1}.
 * </ul>
 *
 * <p>The layout has two regions: a linear region covering
 * {@code 0 .. 2^(groupingPower+1)} where every bucket has width 1 (exact), and
 * a logarithmic region above the cutoff subdivided into
 * {@code 2^groupingPower} buckets per power of two.
 *
 * <p>Values and counts are unsigned 64-bit integers ({@code u64}) carried in
 * Java {@code long}s with unsigned semantics, so the full {@code u64} range is
 * supported, exactly like the Rust crate.
 */
package systems.iop.h2histogram;
