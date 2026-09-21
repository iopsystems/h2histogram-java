package systems.iop.h2histogram.bench;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import systems.iop.h2histogram.*;

/** Reporting phases with all fixtures and caller buffers prepared outside timing. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ReportingBenchmark {
    @Param({"3", "7", "14"})
    public int groupingPower;

    @Param({"few", "all"})
    public String occupancy;

    private Histogram source;
    private Histogram destination;
    private Histogram draining;
    private SparseHistogram sparse;
    private CumulativeHistogram cumulative;
    private List<Histogram> inputs;
    private final double[] requests = {1, 0, .5, .99, .9, .5};
    private final Bucket[] output = new Bucket[requests.length];

    @Setup(Level.Trial)
    public void setup() {
        source = new Histogram(groupingPower, 64);
        Config config = source.config();
        int n = occupancy.equals("few") ? Math.min(64, config.totalBuckets()) : config.totalBuckets();
        for (int i = 0; i < n; i++) {
            int index = (int) ((long) i * config.totalBuckets() / n);
            source.record(config.indexToLowerBound(index), i % 7 + 1);
        }
        destination = new Histogram(config);
        draining = new Histogram(config);
        sparse = source.toSparse();
        cumulative = source.toCumulative();
        inputs = List.of(source, source, source, source);
    }

    @Benchmark public Optional<Bucket> denseScalar() { return source.percentile(.99); }
    @Benchmark public Optional<Bucket> sparseScalar() { return sparse.percentile(.99); }
    @Benchmark public Optional<Bucket> cumulativeScalar() { return cumulative.percentile(.99); }
    @Benchmark public List<PercentileResult> denseBatch() { return source.percentiles(requests); }
    @Benchmark public List<PercentileResult> sparseBatch() { return sparse.percentiles(requests); }
    @Benchmark public List<PercentileResult> cumulativeBatch() { return cumulative.percentiles(requests); }
    @Benchmark public Bucket[] denseInto() { source.percentilesInto(requests, output); return output; }
    @Benchmark public Bucket[] sparseInto() { sparse.percentilesInto(requests, output); return output; }
    @Benchmark public Bucket[] cumulativeInto() { cumulative.percentilesInto(requests, output); return output; }
    @Benchmark public SparseHistogram sparseSnapshot() { return source.toSparse(); }
    @Benchmark public CumulativeHistogram cumulativeSnapshot() { return source.toCumulative(); }
    @Benchmark public Histogram snapshotInto() { source.snapshotInto(destination); return destination; }
    @Benchmark public Histogram resetAfterSnapshot() {
        source.snapshotInto(destination);
        destination.reset();
        return destination;
    }
    @Benchmark public Histogram drainWithRefill() {
        source.snapshotInto(draining);
        draining.drainInto(destination);
        return destination;
    }
    @Benchmark public Histogram checkedAddWithReset() {
        destination.reset();
        destination.checkedAddAssign(source);
        return destination;
    }
    @Benchmark public Histogram ownedSum() { return Histogram.checkedSum(inputs); }
    @Benchmark public SparseHistogram sparseMerge() { return sparse.merge(sparse); }
    @Benchmark public CumulativeHistogram cumulativeMerge() { return cumulative.merge(cumulative); }
    @Benchmark public SparseHistogram sparseDownsample() { return sparse.downsample(groupingPower - 1); }
    @Benchmark public CumulativeHistogram cumulativeDownsample() {
        return cumulative.downsample(groupingPower - 1);
    }
    @Benchmark public SparseHistogram cumulativeToSparse() { return cumulative.toSparse(); }
}
