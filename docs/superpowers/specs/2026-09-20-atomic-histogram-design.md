# AtomicHistogram for h2histogram-java

Date: 2026-09-20
Status: approved design, not yet implemented

## Goal

Let several threads record into one shared histogram without external locking,
with the same small surface and the same semantics as the Rust crate's
`AtomicHistogram`. Queries stay on the existing non-atomic types: callers take
a snapshot, then query the snapshot.

## Decisions

| Question | Decision | Why |
|---|---|---|
| API scope | Mirror Rust: record, plus load and drain into a plain `Histogram` | Smallest surface; identical semantics across ports; no hidden O(buckets) work behind a read |
| Counter width | 64-bit only | The Java port has no 32-bit `Histogram` to snapshot into |
| Storage | `java.util.concurrent.atomic.AtomicLongArray`, private | Idiomatic, lock-free, has the three operations needed; private so a `VarHandle` over `long[]` can replace it later without an API change |
| Performance work | Correct first, then measured with JMH | Numbers inform the README; no tuning beyond the obvious |
| Version | Leave `0.1.1-SNAPSHOT` alone | `RELEASING.md`: versions change only in a `release: vX.Y.Z` PR. This adds public API, so the next release should be `0.2.0` under semver |

Rejected: one `LongAdder` per bucket. `sumThenReset` is not an atomic
capture-and-clear, so increments landing between the sum and the reset are
lost, which breaks the one guarantee `drain` exists to give. Each contended
adder also grows its own cell array, which does not scale to thousands of
buckets.

## Public API

New class `systems.iop.h2histogram.AtomicHistogram`, `public final`, no new
dependencies, Java 17.

```java
public AtomicHistogram(int groupingPower, int maxValuePower)
public AtomicHistogram(Config config)

public Config    config()
public void      increment(long value)
public void      record(long value, long count)
public Histogram load()
public void      loadInto(Histogram destination)
public Histogram drain()
public void      drainInto(Histogram destination)
public String    toString()
```

- `record` is the Rust `add`. The name follows the Java port's `Histogram`.
- Values and counts are unsigned 64-bit carried in `long`, as elsewhere in the
  port. Counts wrap modulo 2^64, like `Histogram.record`.
- No `equals` or `hashCode` override. Comparing live concurrent state is not
  meaningful, so identity semantics are deliberate. Compare snapshots instead.
- Not included: percentiles, `recordMany`, `totalCount`, merge, subtract,
  downsample, conversions. Snapshot first, then use `Histogram`.

### Errors

Following the port's convention, all are `IllegalArgumentException`:

- invalid `groupingPower`/`maxValuePower` (from `Config`);
- `value` out of range for the configuration (from `Config.valueToIndex`);
- `destination.config()` not equal to this histogram's config. The check runs
  before any counter in either histogram is read or written, so a mismatch
  leaves both unchanged.

A `null` destination or config throws `NullPointerException`.

## Concurrency contract

This text belongs in the class Javadoc.

- Every method is safe to call from any thread at any time.
- `increment` and `record` are lock-free: one `getAndAdd` on one counter. No
  other state is written, so there is no cached total, minimum or maximum.
- `load` and `loadInto` read each bucket individually with `getAcquire`. The
  result is not one instantaneous histogram-wide snapshot: writes concurrent
  with a load may or may not be included, bucket by bucket.
- `drain` and `drainInto` do `getAndSet(i, 0)` on each bucket, capturing and
  clearing it in one atomic step. **Every recorded count is returned by
  exactly one drain**: none is lost and none is returned twice, regardless of
  concurrent writers or concurrent drains. A write concurrent with a drain
  lands either in that drain or in a later one.
- There is no instantaneous boundary across buckets. An exact interval
  boundary requires the caller to pause or hand off writers, as in Rust.
- `loadInto` and `drainInto` overwrite every destination bucket, including
  with zeros. The destination `Histogram` is not thread-safe; the caller must
  own it exclusively for the duration of the call.
- Adjacent buckets share cache lines. Threads hammering neighbouring hot
  buckets contend through false sharing. This is inherent to one atomic per
  bucket, is equally true of the Rust type, and is measured rather than
  engineered around.

## Integration

- `loadInto` and `drainInto` write directly into the destination's array via
  the existing package-private `Histogram.bucketsRef()`. `Histogram`'s public
  API does not change.
- `load` and `drain` allocate a `Histogram` and delegate to the `Into` form.
- `README.md`: one row in the API overview table and a short "Concurrent
  recording" section with an example and the contract above in brief.
- `package-info.java`: mention the new type.
- `pom.xml` versions: unchanged.

## Tests

Written before the implementation, in
`src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java`, JUnit 5,
matching the style of `HistogramTest`.

Single-threaded:

1. Constructors accept the same parameters as `Histogram` and reject the same
   invalid ones; `config()` round-trips.
2. After identical `increment`/`record` sequences, `load()` equals a plain
   `Histogram` fed the same inputs, bucket for bucket, across the linear and
   logarithmic regions and at `maxValuePower = 64` with values above
   `Long.MAX_VALUE`.
3. Out-of-range value throws and changes nothing.
4. `load` leaves counters in place: two loads are equal, and a later drain
   still returns everything.
5. `drain` returns the counts and zeroes the histogram: a second drain is
   empty.
6. `loadInto` and `drainInto` overwrite every destination bucket, including
   buckets that were nonzero in the destination and are zero in the source.
7. Config mismatch in `loadInto`/`drainInto` throws and leaves both the atomic
   histogram and the destination unchanged.
8. Counts wrap modulo 2^64.

Multi-threaded:

9. N writer threads each record a known multiset; after joining, `load()`
   equals the single-threaded expected histogram.
10. N writers record a known total while a drainer thread drains in a loop
    into an accumulator; after writers finish, one final drain is added. The
    accumulated histogram must equal the expected histogram exactly. This
    targets the "exactly one drain" guarantee.

Tests 9 and 10 are stress tests. They can demonstrate a violation but cannot
prove its absence; the test comments say so. They use a start latch so threads
contend, bounded work so they finish in well under a second each, and a
timeout so a deadlock fails instead of hanging CI.

## Benchmark

One JMH class, `AtomicRecordBenchmark`, in the existing `benchmarks` module,
following `RecordBenchmark`'s conventions (`maxValuePower = 63`, the same
`gp3`/`gp7` precisions, pre-generated values).

- Single thread: `AtomicHistogram.increment` versus `Histogram.increment`, to
  price the atomic operation with no contention.
- Contended: `@Threads` 1, 2, 4, 8 recording into one shared
  `AtomicHistogram`, with values drawn from a few hot buckets (`few`) and
  spread across many (`all`).
- Baseline alongside: the same thread counts with one plain `Histogram` per
  thread (`Scope.Thread`). The performance study recommends per-writer
  ownership where possible and atomics only when writers must share, so the
  README should show both.

Results go in `benchmarks/README.md` with the host, JDK and JMH settings, and
are described as single-host measurements.

## Out of scope

A 32-bit variant; queries on the atomic type; striped, sharded or per-thread
counter designs; `recordMany`; tuning beyond what the benchmark makes obvious;
formal or exhaustive concurrency testing (JCStress would be the tool if that
is wanted later).
