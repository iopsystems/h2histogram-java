# h2histogram-java

[![CI](https://github.com/iopsystems/h2histogram-java/actions/workflows/ci.yml/badge.svg)](https://github.com/iopsystems/h2histogram-java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A pure-Java implementation of the [iopsystems h2 histogram](https://github.com/iopsystems/histogram).

`h2histogram` produces histograms with **byte-for-byte identical bucketing** to the
Rust `histogram` crate, so histograms recorded here can be consumed by
[Rezolus](https://github.com/iopsystems/rezolus) — and, conversely, you can read an
h2histogram produced by Rezolus (or the [Python](https://github.com/iopsystems/h2histogram-py)
and [Go](https://github.com/iopsystems/h2histogram-go) implementations) and analyze
it on the JVM. Values are carried in Java `long`s with unsigned semantics
(`Long.compareUnsigned` and friends), so the full `u64` value range is supported,
exactly like the Rust crate.

## What is an h2 histogram?

An h2 histogram quantizes values into buckets using two parameters:

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
  <groupId>com.iopsystems</groupId>
  <artifactId>h2histogram</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.iopsystems:h2histogram:0.1.0")
```

The library requires Java 17 or later and has no runtime dependencies.

## Quick start

```java
import com.iopsystems.h2histogram.Bucket;
import com.iopsystems.h2histogram.Histogram;
import com.iopsystems.h2histogram.SparseHistogram;

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
import com.iopsystems.h2histogram.BucketWithQuantiles;
import com.iopsystems.h2histogram.CumulativeHistogram;

CumulativeHistogram c = h.toCumulative(); // read-only; also SparseHistogram.toCumulative()
Bucket b = c.percentile(0.99).orElseThrow(); // O(log n) binary search (individual count)
double mean = c.mean().orElseThrow();        // midpoint-estimated mean, computed once
c.bucketQuantileRange(0);                    // quantile fractions of a stored bucket
for (BucketWithQuantiles bq : c.bucketsWithQuantiles()) {
    // each non-zero bucket with its quantile span
}
```

## API overview

| Type | Purpose |
|------|---------|
| `Config` | Bucketing parameters; `valueToIndex`, `indexToLowerBound`/`indexToUpperBound`, `totalBuckets`, `error` |
| `Histogram` | Dense histogram; `increment`, `record`, `recordMany`, `percentile(s)`, `merge`, `subtract`, `downsample`, `toSparse`, `toCumulative`, `fromBuckets` |
| `SparseHistogram` | Columnar `(index, count)` form; `fromHistogram`, `fromParts`, `toDense`, `toCumulative` |
| `CumulativeHistogram` | Read-only cumulative form (crate's `CumulativeROHistogram`); binary-search `percentile(s)`, `mean`, `bucketQuantileRange`, `bucketsWithQuantiles` |
| `Bucket` | A bucket's `count` and inclusive `[start, end]` range, plus `midpoint`/`width` |

## Unsigned values

All values and counts are unsigned 64-bit integers (`u64`) carried in Java
`long`s. A negative `long` represents a value above `Long.MAX_VALUE`; use
`Long.toUnsignedString`, `Long.parseUnsignedLong`, and `Long.compareUnsigned`
when working near the top of the range. `Bucket.midpoint()` and
`CumulativeHistogram.mean()` already account for this and return correct
`double` estimates across the full range.

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
