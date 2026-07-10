package com.iopsystems.h2histogram.bench;

import com.iopsystems.h2histogram.Config;
import com.iopsystems.h2histogram.Histogram;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Single-threaded record-path comparison between h2histogram and HdrHistogram.
 *
 * <p>The two libraries are compared at matched relative-error levels; because
 * both use a log-linear bucket layout, the matched settings also produce
 * counter arrays of almost identical size, making the cache behaviour
 * directly comparable:
 *
 * <ul>
 *   <li>{@code gp3}: h2 groupingPower=3 (12.5% error, 488 buckets / ~3.8 KiB)
 *       vs HDR 1 significant digit (~10% error, similar array)
 *   <li>{@code gp7}: h2 groupingPower=7 (0.78% error, 7296 buckets / ~57 KiB)
 *       vs HDR 2 significant digits (1% error, 7296-entry array)
 *   <li>{@code gp14}: h2 groupingPower=14 (0.006% error, 819200 buckets /
 *       ~6.3 MiB) vs HDR 4 significant digits (0.01% error, 819200-entry array)
 * </ul>
 *
 * <p>The {@code spread} parameter controls how many distinct buckets the
 * pre-generated value stream touches, moving the hot working set through the
 * cache hierarchy: {@code few} (64 buckets, fits in L1 alongside the data),
 * {@code quarter} (a quarter of all buckets), {@code all} (every bucket).
 * Bucket indices are sampled uniformly — sampling uniform <em>values</em>
 * would concentrate nearly all samples in the top power of two of a
 * logarithmic layout.
 *
 * <p>Histograms use maxValuePower=63 so the identical value stream is in
 * range for HdrHistogram, which only accepts values up to Long.MAX_VALUE.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RecordBenchmark {

    static final int N = 1 << 20;

    @Param({"gp3", "gp7", "gp14"})
    public String precision;

    @Param({"few", "quarter", "all"})
    public String spread;

    long[] values;
    Histogram h2;
    org.HdrHistogram.Histogram hdr;

    @Setup
    public void setup() {
        int groupingPower;
        int hdrDigits;
        switch (precision) {
            case "gp3" -> {
                groupingPower = 3;
                hdrDigits = 1;
            }
            case "gp7" -> {
                groupingPower = 7;
                hdrDigits = 2;
            }
            case "gp14" -> {
                groupingPower = 14;
                hdrDigits = 4;
            }
            default -> throw new IllegalArgumentException(precision);
        }

        Config config = new Config(groupingPower, 63);
        h2 = new Histogram(config);
        hdr = new org.HdrHistogram.Histogram(Long.MAX_VALUE, hdrDigits);

        int totalBuckets = config.totalBuckets();
        int targetBuckets = switch (spread) {
            case "few" -> Math.min(64, totalBuckets);
            case "quarter" -> Math.max(1, totalBuckets / 4);
            case "all" -> totalBuckets;
            default -> throw new IllegalArgumentException(spread);
        };

        Random rng = new Random(42);
        values = new long[N];
        for (int i = 0; i < N; i++) {
            values[i] = config.indexToLowerBound(rng.nextInt(targetBuckets));
        }
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public Histogram h2Record() {
        Histogram h = h2;
        for (long v : values) {
            h.increment(v);
        }
        return h;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public org.HdrHistogram.Histogram hdrRecord() {
        org.HdrHistogram.Histogram h = hdr;
        for (long v : values) {
            h.recordValue(v);
        }
        return h;
    }
}
