---
status: shipped
opened: 2026-09-20
updated: 2026-09-20
---

# AtomicHistogram: a lock-free concurrent recorder

Lifecycle mode: single-PR. This entry was created and closed on the
implementation branch `feat/atomic-histogram`. It replaces the temporary design
spec and implementation plan that the same branch first added under
`docs/superpowers/` and then removed; their durable content is below.

## Goal

Let several threads record into one shared histogram without external locking,
with the same small surface and the same semantics as the Rust crate's
`AtomicHistogram`. Queries stay on the existing non-atomic types: a caller takes
a snapshot, then queries the snapshot.

## Decision Criteria

There was no GO/NO-GO gate on building the type. The gate was on the one
guarantee that justifies it: every recorded count is returned by exactly one
`drain`, with writers running. The change ships only if a test that can detect a
violation of that guarantee exists and passes.

## Scope

In scope: `systems.iop.h2histogram.AtomicHistogram` with `increment`, `record`,
`config`, `load`, `loadInto`, `drain`, `drainInto` and `toString`; tests; README
and package documentation; a JMH benchmark.

Out of scope, by decision:

- A 32-bit variant. The Java port has no 32-bit `Histogram` to snapshot into.
- Percentiles, `recordMany`, `totalCount`, merge and conversions on the atomic
  type. A snapshot supplies all of them, and a percentile on the atomic type
  hides an allocation and a copy of every bucket behind a call that reads as
  cheap.
- `equals` and `hashCode`. Comparing live concurrent state has no stable answer,
  so instances use identity equality. Compare snapshots.
- Striped, sharded or per-thread counter designs.

## Evidence

All paths are relative to the repository root. Commits are on
`feat/atomic-histogram` as rebased onto `ba9177d` for pull request #5. If the
pull request is squashed, find them through it.

| What | Where |
|---|---|
| Recording, `load`, `loadInto` | `893b3b1` |
| `drain`, `drainInto` | `4b01c5e` |
| Two multi-threaded stress tests, README and package docs | `d05730f` |
| Drainer thread liveness assertion | `b59b16b` |
| JMH benchmark, results, benchmarks build fix | `9c788ae` |
| One-thread benchmark paragraph corrected | `6db8a6f` |
| Snapshot publication and destination ownership documented | `74ba743` |
| Drainer thread stopped on every exit path; overlap assertion | `ac575dc` |
| Noise caveat and benchmark Javadoc corrected | `9ecadb6` |
| A run without writer overlap is aborted, not failed | `dc0abb4` |

Test state at `dc0abb4`: `mvn verify` runs 65 tests with 0 failures;
`AtomicHistogramTest` holds 14 of them. Before the rebase onto pull request #4
the total was 50.

## Design and Implementation

### Storage: `AtomicLongArray`

One private `java.util.concurrent.atomic.AtomicLongArray`, one counter per
bucket. It supplies the three operations the type needs:

| Method | Operation | Why this one |
|---|---|---|
| `increment`, `record` | `getAndAdd(index, count)` | One atomic add. No total, minimum or maximum is cached, so recording writes one counter and nothing else. |
| `load`, `loadInto` | `getAcquire(index)` | See below. |
| `drain`, `drainInto` | `getAndSet(index, 0)` | Captures and clears a bucket in one atomic step. This is what delivers the exactly-one-drain guarantee. |

The storage field is private. A `VarHandle` over a plain `long[]`, or a padded
layout, can replace it without an API change.

**Rejected: one `LongAdder` per bucket.** `LongAdder.sumThenReset` is not an
atomic capture-and-clear. An increment that lands between the sum and the reset
is lost, which breaks the exactly-one-drain guarantee. Each contended `LongAdder`
also grows its own cell array, which does not scale to the 7,424 buckets of a
`Config(7, 64)`.

**Why `getAcquire` and not `get` or a plain read.** `getAcquire` is an opaque
read. It is atomic for a `long`, and it observes any write that happens-before
it, including a write followed by `Thread.join`, `Future.get` or a volatile
handoff. The contract promises nothing fresher: "a write concurrent with a load
may or may not be included." A volatile `get` is stronger than the contract
needs. A plain read is not atomic for a `long` on every platform. A code comment
above the read in `loadInto` states this so that a later editor does not change
it.

### Concurrency contract

- Every method is safe to call from any thread, subject to the ownership rule on
  `loadInto` and `drainInto` below.
- Recording is lock-free. The documentation does not claim wait-free:
  `getAndAdd` is one `lock xadd` on x86 and a compare-and-swap loop elsewhere.
- `load` and `drain` visit buckets one at a time. Neither is one instantaneous
  snapshot across all buckets. A write concurrent with a `drain` lands in that
  drain or in a later one. A caller that needs an exact interval boundary must
  pause or hand off its writers, as in the Rust crate.
- The `Histogram` passed to `loadInto` or `drainInto` is not thread-safe. The
  caller must own it exclusively for the duration of the call. Every
  destination bucket is overwritten, including with zero.
- A snapshot returned by `load` or `drain` is an ordinary non-thread-safe
  `Histogram`. Its bucket array is filled after the `Histogram` constructor
  returns, so the final-field freeze does not cover the contents. A caller that
  hands the snapshot to another thread must publish it safely, for example
  through a queue, a volatile field or an executor.
- A destination whose `Config` differs throws `IllegalArgumentException`. The
  check runs before any counter in either histogram is read or written, so a
  rejected `drainInto` clears nothing. A `null` config or destination throws
  `NullPointerException`.

### Integration

`loadInto` and `drainInto` write directly into the destination's array through
the package-private `Histogram.bucketsRef()`, which already existed.
`Histogram`'s public API did not change.

### Version

`pom.xml` and `benchmarks/pom.xml` stay at `0.1.1-SNAPSHOT`. `RELEASING.md`
changes versions only in a `release: vX.Y.Z` pull request. This change adds
public API, so the next release is `0.2.0` under semantic versioning.

### Benchmarks build fix

`benchmarks/pom.xml` declared `jmh-generator-annprocess` only as a `provided`
dependency and relied on `javac` running annotation processors it found on the
classpath. Since JDK 23, `javac` does not. On the JDK 25 used here the JMH
resource `META-INF/BenchmarkList` was never generated, and the benchmark jar
failed at startup with:

```
Unable to find the resource: /META-INF/BenchmarkList
```

`javac -verbose` showed no processor classes loading; an explicit
`-processorpath` generated the list. `9c788ae` adds `annotationProcessorPaths`
for `jmh-generator-annprocess` under `maven-compiler-plugin`. Once a processor
path is set, `javac` stops discovering classpath processors, so the retained
`provided` dependency cannot cause double processing. The defect predated this
change and also broke the existing `RecordBenchmark` on JDK 23 and later. CI did
not detect it because `.github/workflows/ci.yml` builds only the root module.

## Outcome

### The exactly-one-drain guarantee is tested, and the test can fail

`AtomicHistogramTest.everyCountIsReturnedByExactlyOneDrain` runs 8 writer
threads that record 1,600,000 increments in total while a drainer thread drains
in a loop into an accumulator. The accumulated histogram plus one final drain
must equal the single-threaded expected histogram bucket for bucket.

To confirm that the test detects a violation, `drainInto` was temporarily
changed to a non-atomic `get(i)` followed by `set(i, 0)`. The test failed in 5
of 5 runs, with totals such as 1,599,665 against an expected 1,600,000. The
correct `getAndSet` was restored and `AtomicHistogram.java` was confirmed
byte-identical to `4b01c5e`.

The test also requires at least two non-empty drains. After the writers finish
at most one drain can be non-empty, so two or more shows that drains overlapped
with writers. That condition depends on thread scheduling. It was first an
assertion, and it held in 10 of 10 unpinned runs and 5 of 5 runs pinned to two
CPUs. Pinned to one CPU with `taskset -c 0`, it failed 2 of 93 runs with "got
1" while the equality held: the scheduler kept the drainer off the CPU until
the writers had finished. A run without overlap exercised no race, so it is
inconclusive, not wrong. `dc0abb4` checks the equality first and turns the
overlap condition into a JUnit assumption, which reports such a run as aborted.

Both multi-threaded tests are stress tests. A failure proves a defect. A pass
does not prove the absence of a race.

### Benchmark: a shared atomic histogram against one histogram per thread

`AtomicRecordBenchmark` records into one shared `AtomicHistogram`, and
separately into one plain `Histogram` per thread, at 1, 2, 4 and 8 threads.
`few` aims every thread at the same 64 buckets. `all` spreads writes over every
bucket. Scores are average nanoseconds per `increment`, per thread. The full
table is in `benchmarks/README.md`; the endpoints are:

| Precision | Spread | Threads | Shared `AtomicHistogram` | Per-thread `Histogram` |
|---|---|---:|---:|---:|
| gp3 | few | 1 | 6.27 | 2.47 |
| gp3 | few | 8 | 121.42 | 3.11 |
| gp3 | all | 1 | 5.90 | 1.29 |
| gp3 | all | 8 | 39.25 | 1.67 |
| gp7 | few | 1 | 5.39 | 0.41 |
| gp7 | few | 8 | 215.26 | 0.53 |
| gp7 | all | 1 | 6.01 | 1.35 |
| gp7 | all | 8 | 11.67 | 1.84 |

The shared atomic histogram is slower in all 16 cells. From 1 to 8 threads its
per-operation cost grows about 19x (`gp3`) and 40x (`gp7`) under `few`, and
about 6.6x and 1.9x under `all`. The per-thread histograms grow 1.26x to 1.36x.

Recommendation recorded in the README: use `AtomicHistogram` when writers must
share an instance; use one `Histogram` per writer, merged at report time, when
they need not.

Limits of this measurement: one host, one run, a laptop (13th Gen Intel Core
i5-13500H, 4 performance cores and 8 efficiency cores, 16 hardware threads), no
CPU pinning, default frequency governor, 1-minute load average between 0.8 and
3.3 during the sweep, JDK 25.0.4, JMH 1.37. At 8 threads the per-thread average
mixes two core types. The largest relative errors belong to the uncontended
per-thread cells (24% at `gp3`/`few`/4 threads); every shared-atomic cell is at
or below 7.3%. The gaps between the two designs are 2.5x to about 400x, so the
noise does not change the conclusion.

### Two interpretations that were wrong and were corrected

1. **The one-thread gap is not the cost of an atomic add.** The first README
   text said the one-thread rows "price the atomic add itself". The per-thread
   loop reaches 0.41 ns per operation at `gp7`/`few`, about one CPU cycle. A
   loop reaches that only when an out-of-order core overlaps mostly independent
   iterations. `getAndAdd` is a serializing read-modify-write that cannot be
   overlapped the same way, even with no contention. The 2.5x to 13x gap
   therefore combines the atomic instruction's cost with lost instruction-level
   parallelism, and this benchmark cannot separate them (`6db8a6f`, `9ecadb6`).
2. **The noisy cells are the uncontended ones.** The first noise caveat named
   the contended cells and explained the noise as contention. The raw results
   showed the opposite (`9ecadb6`).

The benchmark also cannot distinguish contention on a counter from false
sharing between neighboring counters on one cache line. The README and the
benchmark Javadoc say "contention and/or false sharing".

### Defects in the plan that review found

1. The planned class Javadoc linked `{@link #drain()}` in the commit before
   `drain` existed, which failed the Javadoc build. The link was removed in
   `893b3b1` and restored in `4b01c5e`.
2. The planned drain stress test joined the drainer thread with a 20-second
   timeout and never checked that the thread had finished. A slow drainer would
   race the main thread's read of the accumulated result. `b59b16b` adds a
   liveness assertion. `ac575dc` makes the drainer a daemon thread, sets its
   stop flag in a `finally` block, and asserts that it threw nothing; before
   that, a failing or timed-out test left the drainer spinning for the life of
   the test JVM.

## Derived Documents

- `README.md`: API overview row and the "Concurrent recording" section.
- `src/main/java/systems/iop/h2histogram/package-info.java`: one paragraph.
- `benchmarks/README.md`: the "Concurrent recording: shared atomic versus
  per-thread" section.

## Deferred or Reopen Items

- **Padded or striped counters.** Reopen if a user reports that contention or
  false sharing on hot neighboring buckets limits throughput and cannot give
  each writer its own `Histogram`.
- **A 32-bit atomic variant.** Reopen when the Java port gains a 32-bit
  `Histogram`.
- **Exhaustive concurrency testing.** The stress tests sample interleavings.
  JCStress is the tool for systematic interleaving tests if stronger evidence is
  wanted.
- **An aborted drain stress run is easy to miss.** Surefire reports it as
  skipped. Reopen if CI shows it skipped often; the fix is more work per writer
  or a handshake that holds the writers until the drainer is running.
- **CI does not build `benchmarks/`.** A compile-only CI step for that module
  detects build defects such as the annotation-processor one above.
- **About 100 Javadoc warnings from `SparseHistogram.java`** appear in
  `mvn verify`. They predate this change.
- **The `provided` dependency on `jmh-generator-annprocess`** in
  `benchmarks/pom.xml` is redundant now that the processor path is explicit. It
  preserves the old behavior for a reader who removes the plugin configuration.
- **A cross-host benchmark run** on a pinned, non-hybrid machine, and an
  isolated single-increment measurement, to separate the atomic instruction's
  cost from lost instruction-level parallelism.

## Appendix: Skills Invoked

- `superpowers:brainstorming` — scope, storage choice and design approval.
- `superpowers:writing-plans` — the four-task implementation plan.
- `superpowers:subagent-driven-development` — one implementer and one reviewer
  per task, a whole-branch review, and one fix wave.
- `engineering-journal` — this entry; adopted the journal in this repository.
- `technical-prose` — word choice in this entry.
