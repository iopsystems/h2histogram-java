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
  (`few` at gp7/gp14, where the first 64 buckets are all width-1), h2's
  index computation collapses to a bounds check plus an array increment:
  ~0.76 ns/op, roughly 4× faster than HDR on the same stream.
- **Large working sets favour h2.** At gp14 with the full ~6.3 MiB counter
  array in play (past L2), h2 records ~1.8× faster than HDR at essentially
  the same relative error, thanks to its cheaper index computation ahead of
  the inevitable cache misses.
- **Mixed streams are a wash at moderate sizes.** At gp7/`quarter` the two
  are within noise of each other; HDR is remarkably flat (~2.7–3.0 ns/op)
  whenever its array fits in cache.
- The gp3/`few` cell is the one spread where h2 trails: at grouping power 3
  the linear region is only 16 buckets wide, so a 64-bucket stream mixes
  linear and logarithmic paths and pays for branch misprediction, while at
  `all` the branch becomes predictable-enough again and h2 pulls ahead.
