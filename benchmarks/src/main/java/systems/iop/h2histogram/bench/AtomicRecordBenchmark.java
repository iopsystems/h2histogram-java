package systems.iop.h2histogram.bench;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import systems.iop.h2histogram.AtomicHistogram;
import systems.iop.h2histogram.Config;
import systems.iop.h2histogram.Histogram;

/**
 * Record-path cost of sharing one {@link AtomicHistogram} between threads,
 * against the alternative of one plain {@link Histogram} per thread.
 *
 * <p>Run with {@code -t 1}, {@code -t 2}, {@code -t 4}, {@code -t 8}. At one
 * thread the two benchmarks are the uncontended tight-loop comparison, and
 * that gap includes lost instruction-level parallelism as well as the atomic
 * operation's own cost. At higher thread counts {@code sharedAtomic} shows
 * the cost of sharing (contention and/or false sharing; this benchmark
 * cannot separate them) while {@code perThreadPlain} shows what writer
 * ownership buys.
 *
 * <p>{@code spread=few} aims every thread at the same 64 buckets, which is the
 * worst case for a shared histogram. {@code spread=all} spreads writes over
 * every bucket. Each thread gets its own value stream from a distinct seed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AtomicRecordBenchmark {

    static final int N = 1 << 16;

    @State(Scope.Benchmark)
    public static class Shared {
        @Param({"gp3", "gp7"})
        public String precision;

        @Param({"few", "all"})
        public String spread;

        Config config;
        AtomicHistogram atomic;
        int targetBuckets;
        final AtomicInteger nextSeed = new AtomicInteger(42);

        @Setup
        public void setup() {
            int groupingPower = switch (precision) {
                case "gp3" -> 3;
                case "gp7" -> 7;
                default -> throw new IllegalArgumentException(precision);
            };
            config = new Config(groupingPower, 63);
            atomic = new AtomicHistogram(config);
            targetBuckets = switch (spread) {
                case "few" -> Math.min(64, config.totalBuckets());
                case "all" -> config.totalBuckets();
                default -> throw new IllegalArgumentException(spread);
            };
        }
    }

    @State(Scope.Thread)
    public static class PerThread {
        long[] values;
        Histogram local;

        @Setup
        public void setup(Shared shared) {
            Random rng = new Random(shared.nextSeed.getAndIncrement());
            values = new long[N];
            for (int i = 0; i < N; i++) {
                values[i] = shared.config.indexToLowerBound(rng.nextInt(shared.targetBuckets));
            }
            local = new Histogram(shared.config);
        }
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public AtomicHistogram sharedAtomic(Shared shared, PerThread thread) {
        AtomicHistogram h = shared.atomic;
        for (long v : thread.values) {
            h.increment(v);
        }
        return h;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public Histogram perThreadPlain(PerThread thread) {
        Histogram h = thread.local;
        for (long v : thread.values) {
            h.increment(v);
        }
        return h;
    }
}
