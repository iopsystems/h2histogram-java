# h2histogram-java

[![CI](https://github.com/iopsystems/h2histogram-java/actions/workflows/ci.yml/badge.svg)](https://github.com/iopsystems/h2histogram-java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A pure-Java implementation of the [h2histogram design](https://github.com/iopsystems/histogram).

`h2histogram` produces histograms with **byte-for-byte identical bucketing** to the
Rust `histogram` crate, so histograms recorded here can be consumed by
[Rezolus](https://github.com/iopsystems/rezolus) — and, conversely, you can read an
h2histogram produced by Rezolus (or the [Python](https://github.com/iopsystems/h2histogram-py)
and [Go](https://github.com/iopsystems/h2histogram-go) implementations) and analyze
it on the JVM. Values are carried in Java `long`s with unsigned semantics
(`Long.compareUnsigned` and friends), so the full `u64` value range is supported,
exactly like the Rust crate.

## What is h2histogram?

h2histogram quantizes values into buckets using two parameters:

- **`groupingPower`** — the number of buckets spanning each power of two. It sets
  the relative error to `2^-groupingPower` (e.g. `groupingPower=7` → ~0.78% error).
- **`maxValuePower`** — the largest representable value is `2^maxValuePower - 1`.

Values below `2^(groupingPower+1)` are stored **exactly** (linear buckets of width 1);
larger values fall into logarithmic buckets. This gives HDR-histogram-like guarantees
with a simpler, faster bucket index computation. Rezolus records histograms with
`groupingPower=3` and `maxValuePower=64`.

## Install

Maven:

```xml
<dependency>
  <groupId>systems.iop</groupId>
  <artifactId>h2histogram</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("systems.iop:h2histogram:0.1.0")
```

The library requires Java 17 or later and has no runtime dependencies.

## Quick start

```java
import systems.iop.h2histogram.Bucket;
import systems.iop.h2histogram.Histogram;
import systems.iop.h2histogram.SparseHistogram;

Histogram h = new Histogram(7, 64); // groupingPower, maxValuePower

h.increment(42);
h.record(1000, 5);                   // value, count
h.recordMany(new long[] {12, 15, 900}); // bulk

System.out.println(h.totalCount()); // 9

Bucket p99 = h.percentile(0.99).orElseThrow(); // Optional.empty() if empty
System.out.println(p99.start() + " " + p99.end() + " " + p99.midpoint());

// Combine / reduce
Histogram merged = h.merge(other);   // element-wise sum
Histogram coarse = h.downsample(4);  // fewer buckets, higher error, same total count
SparseHistogram sparse = h.toSparse(); // columnar (index, count) form for storage
```

### Fast repeated quantile queries

For a snapshot you'll query many times, convert to a `CumulativeHistogram`
(the crate's `CumulativeROHistogram`). It stores non-zero buckets with
**cumulative** counts, so percentiles are answered with a binary search, and it
precomputes a midpoint-estimated mean:

```java
import systems.iop.h2histogram.BucketWithQuantiles;
import systems.iop.h2histogram.CumulativeHistogram;

CumulativeHistogram c = h.toCumulative(); // read-only; also SparseHistogram.toCumulative()
Bucket b = c.percentile(0.99).orElseThrow(); // O(log n) binary search (individual count)
double mean = c.mean().orElseThrow();        // midpoint-estimated mean, computed once
c.bucketQuantileRange(0);                    // quantile fractions of a stored bucket
for (BucketWithQuantiles bq : c.bucketsWithQuantiles()) {
    // each non-zero bucket with its quantile span
}
```

## Reporting workflow

Keep dense histograms for recording. At an interval boundary, exclusively own the
recorder or hold your application lock while copying or draining it into reusable
dense storage. Aggregate reports with checked operations, then choose a sparse
snapshot for storage or a cumulative snapshot for repeated queries:

```java
import java.util.List;

Histogram interval = new Histogram(h.config()); // allocate once, reuse each interval
h.drainInto(interval);                          // replace interval counts, clear h
Histogram report = Histogram.checkedSum(List.of(interval, interval));
CumulativeHistogram snapshot = report.toCumulative();
double[] requests = {0.99, 0.5, 1.0, 0.5};       // caller can retain both arrays
Bucket[] output = new Bucket[requests.length];
int written = snapshot.percentilesInto(requests, output);
CumulativeHistogram coarse = snapshot.downsample(4);
SparseHistogram stored = coarse.toSparse();
```

`reset()` retains the dense counter array. `snapshotInto(destination)` replaces
counts in a compatible existing destination and permits self-copy;
`drainInto(destination)` additionally clears the source and rejects self-drain.
They validate compatibility before mutation. These operations are **not concurrent
or atomic drains**: dense histograms are mutable and require exclusive ownership
or external synchronization, including throughout two-pass checked addition.

`checkedAddAssign(other)` checks every unsigned bucket addition before changing
any destination count, including when `other == this`. `Histogram.checkedSum(List<Histogram>)`
validates every configuration before allocating its independent output, copies the
first source directly, then checks and adds each remaining source in one pass.
It rejects an empty list, copies a singleton, and counts repeated references
repeatedly without mutating any input. Mismatch throws `IllegalArgumentException`;
unsigned bucket overflow throws `ArithmeticException`.

All three representations provide direct `percentile(double)` and
`percentilesInto(double[], Bucket[])`. The latter returns the number written,
retains request order and duplicates, and leaves unused output entries untouched.
An empty histogram writes nothing and returns zero. Invalid requests (including
NaN) and insufficient capacity are rejected before writing. Dense and sparse
scalar queries scan counts; their buffer methods scan once for the total and once
per request. Dense and sparse `percentiles(double...)` sort and deduplicate
requests, scan their respective counts once, then restore request order and
duplicates. Cumulative queries use binary search. Scalar methods bypass batch containers, and buffer methods avoid
request/result collections, but returned `Bucket` objects still allocate.

`SparseHistogram.merge` and `downsample` work on sorted sparse arrays.
`CumulativeHistogram.merge`, `downsample`, and `toSparse` use sparse arrays and
prefix differences without constructing dense histograms. Transforms produce
independent outputs, check unsigned overflow, and recompute cumulative means
from the resulting bucket bounds. Downsampling requires a strictly smaller grouping
power. Cumulative transforms currently create intermediate sparse arrays.

Sparse and cumulative snapshots use fixed-size arrays; produced snapshots have
exactly one entry per retained bucket. A shrink-to-fit API is therefore not applicable.
Public array accessors return copies, so callers cannot invalidate cumulative means.
Raw imports validate lengths, index range/order, and unsigned cumulative monotonicity.
Sparse imports accept zero counts, validate every supplied index first, then omit
zero entries from their exact-sized stored arrays; an all-zero import is empty.
Cumulative imports retain acceptance of repeated positive prefixes; conversion to
sparse and native transforms omit their zero-count entries.

See [reporting benchmarks](benchmarks/README.md#reporting-and-analytics-phases) for
separate query, construction, reuse, and transform measurements. Java remains
Java 17-compatible with no runtime dependencies, JNI, or incubating Vector API.
No Rust speedup is assumed for JVM execution.

## API overview

| Type | Purpose |
|------|---------|
| `Config` | Bucketing parameters; `valueToIndex`, `indexToLowerBound`/`indexToUpperBound`, `totalBuckets`, `error` |
| `Histogram` | Dense histogram; `increment`, `record`, `recordMany`, `percentile(s)`, `merge`, `subtract`, `downsample`, `toSparse`, `toCumulative`, `fromBuckets`, `reset`, `snapshotInto`, `drainInto`, `checkedAddAssign`, `checkedSum`, `percentilesInto` |
| `AtomicHistogram` | Lock-free concurrent recorder (crate's `AtomicHistogram`); `increment`, `record`, `load`, `loadInto`, `drain`, `drainInto`. Snapshot to a `Histogram` to query |
| `SparseHistogram` | Columnar `(index, count)` form; `fromHistogram`, `fromParts`, `toDense`, `toCumulative`, `percentile(s)`, `percentilesInto`, `merge`, `downsample` |
| `CumulativeHistogram` | Read-only cumulative form (crate's `CumulativeROHistogram`); binary-search `percentile(s)`, `mean`, `bucketQuantileRange`, `bucketsWithQuantiles`, `percentilesInto`, `merge`, `downsample`, `toSparse` |
| `Bucket` | A bucket's `count` and inclusive `[start, end]` range, plus `midpoint`/`width` |

## Concurrent recording

`Histogram` is not thread-safe. When several threads must record into one
shared instance, use `AtomicHistogram`, the analogue of the crate's
`AtomicHistogram`. It only records; take a snapshot to query.

```java
import systems.iop.h2histogram.AtomicHistogram;
import systems.iop.h2histogram.Histogram;

AtomicHistogram shared = new AtomicHistogram(7, 64);

// any number of threads
shared.increment(latencyNanos);

// a reporter thread, every interval
Histogram interval = shared.drain();          // capture and reset
Bucket p99 = interval.percentile(0.99).orElseThrow();

// or reuse one snapshot buffer to avoid allocating per interval
Histogram scratch = new Histogram(shared.config());
shared.drainInto(scratch);
```

- Recording is lock-free: one atomic add per call.
- `drain` captures and clears each bucket in one atomic step, so every recorded
  count is returned by exactly one drain. None is lost or double-counted, even
  while writers are running.
- `load` copies without resetting. Neither `load` nor `drain` is one
  instantaneous snapshot across all buckets; a write concurrent with a
  snapshot may land on either side of it, bucket by bucket. If you need an
  exact interval boundary, pause or hand off the writers yourself.
- A snapshot returned by `load` or `drain` is an ordinary non-thread-safe
  `Histogram`; hand it to other threads safely (for example through a queue,
  a volatile field, or an executor). The destination passed to `loadInto` or
  `drainInto` must be owned exclusively by the caller for the duration of the
  call.
- If each thread can own its histogram, one plain `Histogram` per thread,
  merged when reporting, is faster. See [benchmarks](benchmarks/README.md).

## Unsigned values

All values and counts are unsigned 64-bit integers (`u64`) carried in Java
`long`s. A negative `long` represents a value above `Long.MAX_VALUE`; use
`Long.toUnsignedString`, `Long.parseUnsignedLong`, and `Long.compareUnsigned`
when working near the top of the range. `Bucket.midpoint()` and
`CumulativeHistogram.mean()` already account for this and return correct
`double` estimates across the full range.

Existing dense `record`, `increment`, `merge`, `downsample`, and dense/sparse
`totalCount()` retain modulo-2^64 arithmetic. Checked aggregation checks individual
buckets; their combined total can still exceed u64 even when each bucket fits.
Percentile queries and cumulative construction reject such total overflow with
`ArithmeticException` instead of reporting from wrapped prefixes. Negative `long`
counts are valid unsigned values, not invalid negative observations. Java/Go counts
are u64, JavaScript counts are bounded by exact safe integers, and Python counts
are arbitrary-precision integers; callers exchanging data must respect those limits.

## Compatibility across implementations

The same bucketing is implemented in:

- [Rust](https://github.com/iopsystems/histogram) — the canonical implementation
- [Python](https://github.com/iopsystems/h2histogram-py)
- [Go](https://github.com/iopsystems/h2histogram-go)
- [JavaScript](https://github.com/iopsystems/h2histogram-js) (limited to `maxValuePower <= 53`,
  since JS numbers are 64-bit floats)
- Java (this repository) — full `u64` range

Because the bucket indices are identical, a `(bucket_indices, bucket_counts)`
pair produced by any of these can be loaded via `SparseHistogram.fromParts` /
`CumulativeHistogram.fromParts` and analyzed here.

## Correctness

The bucketing math is verified against the exact assertions from the Rust crate's
own unit tests (`src/config.rs`), so the bucketing is guaranteed bit-identical.
Run `mvn test` to see for yourself.

## Building

```bash
mvn verify
```

## Releasing

Releases are published to Maven Central automatically via GitHub Actions when
a GitHub Release is published. See [RELEASING.md](RELEASING.md) for the steps.

## License

MIT — see [LICENSE](LICENSE).
