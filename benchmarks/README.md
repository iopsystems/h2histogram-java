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
