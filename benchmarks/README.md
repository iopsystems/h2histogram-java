# Benchmarks

JMH benchmarks comparing `h2histogram` against
[HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) on the
single-threaded record path.

## Method

The two libraries are compared at matched relative-error levels. Because both
use a log-linear bucket layout, the matched settings also produce counter
arrays of almost identical size, so the cache behaviour is directly
comparable:

| Precision | h2histogram | HdrHistogram | Counter array |
|-----------|-------------|--------------|---------------|
| `gp3` | `groupingPower=3` (12.5% error) | 1 significant digit (~10%) | ~4 KiB |
| `gp7` | `groupingPower=7` (0.78% error) | 2 significant digits (1%) | ~57 KiB |
| `gp14` | `groupingPower=14` (0.006% error) | 4 significant digits (0.01%) | ~6.3 MiB |

The `spread` parameter controls how many distinct buckets the pre-generated
value stream touches, moving the hot working set through the cache hierarchy:

- **`few`** — 64 buckets (512 B of counters; L1)
- **`quarter`** — a quarter of all buckets
- **`all`** — every bucket (up to ~6.3 MiB of counters at `gp14`; L3/DRAM)

Bucket indices are sampled uniformly; sampling uniform *values* would
concentrate nearly all samples in the top power of two of a logarithmic
layout. Both histograms are fed the identical value stream, and use
`maxValuePower=63` / `highestTrackableValue=Long.MAX_VALUE` so every value is
in range for both.

## Running

```bash
# from the repository root: install the library, then build and run
mvn -B install -DskipTests
cd benchmarks
mvn -B package
java -jar target/benchmarks.jar
```

Useful options: `-p precision=gp7 -p spread=all` to run a single cell,
`-prof perfnorm` for per-op cache-miss counters (Linux), `-rf json` for
machine-readable output.

The compiler plugin declares `jmh-generator-annprocess` explicitly via
`annotationProcessorPaths`, because JDK 23+ no longer runs annotation
processors that are only on the classpath, and without it JMH's benchmark
list would not be generated.

## Results

Single-threaded, average time per recorded value (lower is better). Measured
on a 4-vCPU Intel Xeon @ 2.80 GHz cloud instance (32 KiB L1d / 1 MiB L2 per
core, 33 MiB shared L3), OpenJDK 21, JMH 1.37, 5×1 s iterations after 3×1 s
warmup, one fork. Treat the numbers as indicative — this is a shared cloud
machine.

| Precision | Spread | h2histogram (ns/op) | HdrHistogram (ns/op) |
|-----------|--------|--------------------:|---------------------:|
| gp3 (~4 KiB) | few | 3.77 ± 0.37 | 2.90 ± 0.12 |
| gp3 | quarter | 2.70 ± 0.08 | 2.88 ± 0.12 |
| gp3 | all | 1.99 ± 0.07 | 2.86 ± 0.23 |
| gp7 (~57 KiB) | few | 0.76 ± 0.02 | 2.94 ± 0.27 |
| gp7 | quarter | 2.74 ± 0.09 | 2.72 ± 0.04 |
| gp7 | all | 2.03 ± 0.16 | 2.82 ± 0.12 |
| gp14 (~6.3 MiB) | few | 0.76 ± 0.03 | 3.01 ± 0.19 |
| gp14 | quarter | 3.47 ± 0.15 | 5.05 ± 0.23 |
| gp14 | all | 3.86 ± 0.88 | 6.82 ± 0.65 |

Observations:

- **Linear-region fast path.** When every value lands in the linear region
  (`few` at gp7/gp14, where the first 64 buckets are all width-1), h2histogram's
  index computation collapses to a bounds check plus an array increment:
  ~0.76 ns/op, roughly 4× faster than HDR on the same stream.
- **Large working sets favour h2histogram.** At gp14 with the full ~6.3 MiB counter
  array in play (past L2), h2histogram records ~1.8× faster than HDR at essentially
  the same relative error, thanks to its cheaper index computation ahead of
  the inevitable cache misses.
- **Mixed streams are a wash at moderate sizes.** At gp7/`quarter` the two
  are within noise of each other; HDR is remarkably flat (~2.7–3.0 ns/op)
  whenever its array fits in cache.
- The gp3/`few` cell is the one spread where h2histogram trails: at grouping power 3
  the linear region is only 16 buckets wide, so a 64-bucket stream mixes
  linear and logarithmic paths and pays for branch misprediction, while at
  `all` the branch becomes predictable-enough again and h2histogram pulls ahead.

## Reporting and analytics phases

`ReportingBenchmark` is separate from the existing `RecordBenchmark`; the historical
recording results above do not measure these new APIs. Run on an otherwise idle
machine, choosing precision and occupancy independently:

```bash
mvn -B install
mvn -B -f benchmarks/pom.xml package
java -jar benchmarks/target/benchmarks.jar ReportingBenchmark \
  -p groupingPower=7 -p occupancy=few -prof gc -rf json -rff reporting.json
# Fast harness verification only; not sufficient for performance conclusions:
java -jar benchmarks/target/benchmarks.jar ReportingBenchmark \
  -p groupingPower=7 -p occupancy=few -wi 0 -i 1 -r 50ms -f 1
```

Fixtures, input lists, request arrays, and output buffers are prepared at trial
setup outside timing. Occupancy `few` uses 64 buckets spread across the full
configuration; `all` fills every bucket. Every invocation measures one report
operation (or one explicitly named combined lifecycle operation), not one observation.

- `denseScalar`, `sparseScalar`, `cumulativeScalar`: one p99 lookup, including the
  Optional/Bucket result. `*Batch` includes result-list/record construction for six
  requests; `*Into` reuses arrays but still creates Bucket results.
- `sparseSnapshot`, `cumulativeSnapshot`: snapshot construction, including scans,
  arrays, and cumulative mean computation. `snapshotInto`: copy into existing dense
  storage. `resetAfterSnapshot`: refill plus reset; `drainWithRefill`: refill plus
  drain; `checkedAddWithReset`: reset plus checked addition. Refill/reset costs are
  deliberately inside timing and must not be mistaken for isolated operation costs.
- `ownedSum`: four references to the prepared source, including owned output creation.
  `sparseMerge`, `cumulativeMerge`, `*Downsample`, `cumulativeToSparse`: native
  transform output construction, including temporary arrays and recomputed means.

JMH consumes returned results. Java reclamation is asynchronous; `-prof gc` reports
allocation and collection effects rather than a separately timed destructor. Use
multiple forks and normal warmup/measurement durations for conclusions. Array reuse
is not an allocation-free guarantee and no port-specific speedup is claimed here.

## Concurrent recording: shared atomic versus per-thread

`AtomicRecordBenchmark` compares recording into one shared `AtomicHistogram`
against one plain `Histogram` per thread, at 1, 2, 4 and 8 threads.

```bash
java -jar target/benchmarks.jar AtomicRecordBenchmark -t 4
```

`spread=few` aims every thread at the same 64 buckets, the worst case for a
shared histogram. `spread=all` spreads writes over every bucket (488 buckets
at `gp3`, 7296 at `gp7`, for the `Config(groupingPower, 63)` used here).
Scores are average ns per `increment`, per thread; lower is better.

Environment: `13th Gen Intel(R) Core(TM) i5-13500H`, 16 hardware threads,
`openjdk version "25.0.4" 2026-07-21`, JMH 1.37, 3 x 1 s warmup, 5 x 1 s
measurement, 1 fork. This is a laptop, not a benchmark host: it is a hybrid
part (4 performance cores / 8 threads, plus 8 efficiency cores / 8 threads,
16 threads total), so at `-t 8` some benchmark threads may land on efficiency
cores and the per-thread average mixes two core types; no CPU pinning was
used. The machine was under light background load during the run (1-minute
load average moved between roughly 0.8 and 3.3 across the sweep, driven
mostly by the sweep's own prior runs decaying between measurements) with the
default frequency governor. These are single-host, single-run measurements;
treat absolute values as indicative, not reproducible to the last digit.
`Score Error (99.9%)` from the raw CSVs (not reproduced here) is small
relative to the scores for nearly every cell, so the trends below are not
noise artifacts, with the caveat that a few `gp3`/`few` and `gp7`/`few` cells
at 4 and 8 threads have wider (but still small relative to score) confidence
intervals, consistent with contention itself being noisy.

| Precision | Spread | Threads | shared `AtomicHistogram` | per-thread `Histogram` |
|---|---|---:|---:|---:|
| gp3 | few | 1 | 6.27 | 2.47 |
| gp3 | few | 2 | 25.08 | 2.48 |
| gp3 | few | 4 | 55.49 | 2.65 |
| gp3 | few | 8 | 121.42 | 3.11 |
| gp3 | all | 1 | 5.90 | 1.29 |
| gp3 | all | 2 | 12.09 | 1.32 |
| gp3 | all | 4 | 24.01 | 1.36 |
| gp3 | all | 8 | 39.25 | 1.67 |
| gp7 | few | 1 | 5.39 | 0.41 |
| gp7 | few | 2 | 27.00 | 0.43 |
| gp7 | few | 4 | 55.62 | 0.43 |
| gp7 | few | 8 | 215.26 | 0.53 |
| gp7 | all | 1 | 6.01 | 1.35 |
| gp7 | all | 2 | 7.44 | 1.35 |
| gp7 | all | 4 | 8.53 | 1.41 |
| gp7 | all | 8 | 11.67 | 1.84 |

These are measurements from one host and one run, so treat them as
indicative of shape, not precise multipliers. The one-thread rows measure
per-operation throughput of a tight loop of 65,536 increments with no
contention, and the gap there is not negligible: `sharedAtomic` costs 2.5x to
13x what `perThreadPlain` costs at one thread, depending on the cell, and the
gap is largest exactly where the plain path is cheapest (`gp7`/`few`, where
`perThreadPlain`'s linear fast path is ~0.41 ns/op but `sharedAtomic` is
still ~5.39 ns/op). This gap is not a clean price of the atomic instruction
itself: `Histogram.increment` is a plain `buckets[idx] += count`, and a loop
of independent, data-dependent-indexed increments like that can overlap
across iterations on an out-of-order core, while `AtomicHistogram.increment`
is a serializing read-modify-write that cannot overlap the same way even
when uncontended — the ~0.41 ns/op plain score at `gp7`/`few` (roughly one
CPU cycle) is itself only reachable with that cross-iteration overlap, so
some of the measured gap is lost instruction-level parallelism rather than
the atomic op's own cost, and this benchmark cannot separate the two. It is
still the relevant comparison for a real recording loop, but an isolated
single increment, run once rather than in a tight loop, would likely show a
smaller ratio than the tight loop does here — we did not measure that case.
Above one thread, `sharedAtomic` grows
substantially faster than thread count at `spread=few` (about 19x from 1 to 8
threads at `gp3`, about 40x at `gp7`), consistent with contention on a
64-bucket target shared by every thread. At `spread=all`, where writes land
across many more distinct buckets (488 at `gp3`, 7296 at `gp7`),
`sharedAtomic` still grows with thread count but far less steeply (about 6.6x
at `gp3`, about 1.9x at `gp7`), and the largest bucket count (`gp7`/`all`)
shows the smallest growth of any cell — consistent with more buckets diluting
contention, though a throughput benchmark like this cannot distinguish
memory-level contention from cache-line (false) sharing between neighbouring
counters. `perThreadPlain` stays far flatter across the sweep (roughly
1.26x-1.36x from 1 to 8 threads in every cell) but is not perfectly flat
either, which is consistent with general memory-bandwidth or scheduling
pressure from running more concurrent threads rather than with any data
sharing, since each thread's `Histogram` is private. Use `AtomicHistogram`
when writers must share an instance; prefer one `Histogram` per writer,
merged at report time, when they need not — the gap between the two grows
with thread count and is already substantial at just two threads whenever
`spread=few` applies.
