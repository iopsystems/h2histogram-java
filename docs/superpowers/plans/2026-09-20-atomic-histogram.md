# AtomicHistogram Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `AtomicHistogram`, a lock-free histogram several threads can record into, with `load`/`drain` snapshots into the existing plain `Histogram`.

**Architecture:** One new final class backed by a private `java.util.concurrent.atomic.AtomicLongArray`, one counter per bucket. Recording is a single `getAndAdd`; `load` reads with `getAcquire`; `drain` uses `getAndSet(i, 0)` so each count is captured by exactly one drain. Snapshots write straight into a `Histogram`'s array through the existing package-private `Histogram.bucketsRef()`. No existing public API changes.

**Tech Stack:** Java 17, Maven, JUnit 5 (`junit-jupiter` 5.10.2), JMH 1.37 in the separate `benchmarks` module.

**Spec:** `docs/superpowers/specs/2026-09-20-atomic-histogram-design.md`

## Global Constraints

- Java release level is 17 (`maven.compiler.release`). Use nothing newer. CI runs JDK 17 and 21.
- No new runtime dependencies. The library has none today.
- Package is `systems.iop.h2histogram`. The new class is `public final`.
- Errors are `IllegalArgumentException`, matching the rest of the port. A `null` config or destination throws `NullPointerException`.
- Values and counts are unsigned 64-bit carried in `long`. Counts wrap modulo 2^64.
- Method names follow the Java port, not Rust: `record(value, count)`, not `add`.
- Do **not** change `<version>` in `pom.xml` or `benchmarks/pom.xml`. `RELEASING.md`: versions change only in a `release: vX.Y.Z` PR.
- Do not add `equals`, `hashCode`, percentiles, `recordMany` or `totalCount` to `AtomicHistogram`.
- Work on branch `feat/atomic-histogram`. Commit after each task. Do not push.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

## Prerequisite

Maven is not installed on the development machine (`command -v mvn` prints nothing). Install it before Task 1:

```bash
sudo apt install maven
mvn -v        # expect Apache Maven 3.x and a Java 17+ runtime
```

Then confirm the baseline is green from the repository root:

```bash
mvn -B --no-transfer-progress verify
```

Expected: `BUILD SUCCESS`, with `ConfigTest`, `CumulativeHistogramTest`, `ExampleTest` and `HistogramTest` passing.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/systems/iop/h2histogram/AtomicHistogram.java` | Create | The concurrent recorder and its snapshot methods |
| `src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java` | Create | Single-threaded contract tests and two multi-threaded stress tests |
| `src/main/java/systems/iop/h2histogram/package-info.java` | Modify | Mention the new type |
| `README.md` | Modify | API table row and a "Concurrent recording" section |
| `benchmarks/src/main/java/systems/iop/h2histogram/bench/AtomicRecordBenchmark.java` | Create | JMH: shared atomic versus per-thread plain histograms |
| `benchmarks/README.md` | Modify | How to run it and the measured results |

---

### Task 1: Concurrent recording and `load`

**Files:**
- Create: `src/main/java/systems/iop/h2histogram/AtomicHistogram.java`
- Create: `src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java`

**Interfaces:**
- Consumes (already exist): `Config(int, int)`, `Config.totalBuckets()`, `Config.valueToIndex(long)` (throws `IllegalArgumentException` when out of range, before returning), `Config.equals`, `Histogram(Config)`, `Histogram.config()`, `Histogram.bucketCounts()`, `Histogram.equals`, and package-private `long[] Histogram.bucketsRef()`.
- Produces: `AtomicHistogram(int, int)`, `AtomicHistogram(Config)`, `Config config()`, `void increment(long)`, `void record(long, long)`, `Histogram load()`, `void loadInto(Histogram)`, `String toString()`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java`:

```java
package systems.iop.h2histogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AtomicHistogramTest {

    @Test
    void constructorsAndConfig() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        assertEquals(new Config(7, 64), a.config());

        Config c = new Config(3, 32);
        assertEquals(c, new AtomicHistogram(c).config());

        assertThrows(IllegalArgumentException.class, () -> new AtomicHistogram(7, 65));
        assertThrows(IllegalArgumentException.class, () -> new AtomicHistogram(64, 64));
        assertThrows(IllegalArgumentException.class, () -> new AtomicHistogram(10, 5));
        assertThrows(NullPointerException.class, () -> new AtomicHistogram(null));
    }

    @Test
    void loadMatchesPlainHistogram() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        Histogram expected = new Histogram(7, 64);

        // Linear region, the cutoff, logarithmic region, and values above
        // Long.MAX_VALUE (negative longs are large unsigned values).
        long[] values = {0, 1, 2, 255, 256, 257, 1000, 1L << 40, (1L << 40) + 1,
            Long.MAX_VALUE, Long.MIN_VALUE, -1L};
        for (int i = 0; i < values.length; i++) {
            long count = i + 1;
            a.record(values[i], count);
            expected.record(values[i], count);
        }
        for (long v = 0; v < 600; v++) {
            a.increment(v);
            expected.increment(v);
        }

        assertEquals(expected, a.load());
    }

    @Test
    void outOfRangeThrowsAndChangesNothing() {
        AtomicHistogram a = new AtomicHistogram(7, 10); // max value is 1023
        a.increment(1023);
        assertThrows(IllegalArgumentException.class, () -> a.increment(1024));
        assertThrows(IllegalArgumentException.class, () -> a.record(-1L, 5));

        Histogram expected = new Histogram(7, 10);
        expected.increment(1023);
        assertEquals(expected, a.load());
    }

    @Test
    void loadLeavesCountersInPlace() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(42, 3);
        Histogram first = a.load();
        Histogram second = a.load();
        assertEquals(first, second);
        assertEquals(3, second.totalCount());
    }

    @Test
    void loadIntoOverwritesEveryDestinationBucket() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);

        // The destination holds a count in a bucket that is zero in the source.
        Histogram destination = new Histogram(7, 64);
        destination.record(5000, 9);
        a.loadInto(destination);

        Histogram expected = new Histogram(7, 64);
        expected.record(100, 4);
        assertEquals(expected, destination);
    }

    @Test
    void loadIntoRejectsMismatchedConfigAndChangesNothing() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        Histogram destination = new Histogram(3, 64);
        destination.record(7, 2);
        Histogram before = Histogram.fromBuckets(3, 64, destination.bucketCounts());

        assertThrows(IllegalArgumentException.class, () -> a.loadInto(destination));
        assertEquals(before, destination);
        assertEquals(4, a.load().totalCount());
        assertThrows(NullPointerException.class, () -> a.loadInto(null));
    }

    @Test
    void countsWrapModulo2To64() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(5, -1L); // 2^64 - 1
        a.record(5, 2);
        assertEquals(1, a.load().bucketCounts()[a.config().valueToIndex(5)]);
    }

    @Test
    void toStringNamesTheConfiguration() {
        assertEquals("AtomicHistogram(grouping_power=7, max_value_power=64)",
                new AtomicHistogram(7, 64).toString());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -B --no-transfer-progress -Dtest=AtomicHistogramTest test`
Expected: `COMPILATION ERROR`, "cannot find symbol ... class AtomicHistogram".

- [ ] **Step 3: Write the implementation**

Create `src/main/java/systems/iop/h2histogram/AtomicHistogram.java`:

```java
package systems.iop.h2histogram;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A histogram whose buckets are atomic counters, so several threads can record
 * into one shared instance without external locking.
 *
 * <p>This is the Java analogue of the Rust {@code AtomicHistogram}. Unlike
 * {@link Histogram} it cannot report percentiles directly. Take a non-atomic
 * snapshot with {@link #load()} or {@link #drain()} and query that.
 *
 * <h2>Concurrency contract</h2>
 *
 * <ul>
 *   <li>Every method is safe to call from any thread at any time.
 *   <li>{@link #increment(long)} and {@link #record(long, long)} are lock-free:
 *       one atomic add on one counter. No total, minimum or maximum is cached.
 *   <li>{@link #load()} and {@link #loadInto(Histogram)} read each bucket
 *       individually. The result is not one instantaneous histogram-wide
 *       snapshot: a write concurrent with a load may or may not be included.
 * </ul>
 *
 * <p>There is no instantaneous boundary across buckets. An exact interval
 * boundary requires the caller to pause or hand off writers.
 *
 * <p>Adjacent buckets share cache lines, so threads recording into
 * neighbouring hot buckets contend through false sharing. Where each writer
 * can own its histogram, one plain {@link Histogram} per thread merged later
 * is faster; use this type when writers must share an instance.
 *
 * <p>Values and counts are unsigned 64-bit integers carried in {@code long}s.
 * Counts wrap modulo 2^64. Instances use identity equality: compare snapshots,
 * not live histograms.
 */
public final class AtomicHistogram {
    private final Config config;
    private final AtomicLongArray buckets;

    /**
     * Creates an empty atomic histogram.
     *
     * @throws IllegalArgumentException if the parameters are invalid (see
     *     {@link Config#Config(int, int)})
     */
    public AtomicHistogram(int groupingPower, int maxValuePower) {
        this(new Config(groupingPower, maxValuePower));
    }

    /** Creates an empty atomic histogram from an existing {@link Config}. */
    public AtomicHistogram(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.buckets = new AtomicLongArray(config.totalBuckets());
    }

    /** Returns the bucketing configuration. */
    public Config config() {
        return config;
    }

    /**
     * Adds one observation of {@code value}.
     *
     * @throws IllegalArgumentException if value is out of range; no counter changes
     */
    public void increment(long value) {
        record(value, 1);
    }

    /**
     * Adds {@code count} observations of {@code value}.
     *
     * @throws IllegalArgumentException if value is out of range; no counter changes
     */
    public void record(long value, long count) {
        // valueToIndex validates before returning, so a bad value never reaches
        // the counters.
        buckets.getAndAdd(config.valueToIndex(value), count);
    }

    /** Copies the current bucket values into a new {@link Histogram}. */
    public Histogram load() {
        Histogram snapshot = new Histogram(config);
        loadInto(snapshot);
        return snapshot;
    }

    /**
     * Overwrites {@code destination} with the current bucket values, reusing
     * its storage. Every destination bucket is replaced, including with zero.
     * The caller must own {@code destination} exclusively during the call.
     *
     * @throws IllegalArgumentException if the configurations differ; neither
     *     histogram is changed
     */
    public void loadInto(Histogram destination) {
        long[] out = checkedDestination(destination);
        for (int i = 0; i < out.length; i++) {
            out[i] = buckets.getAcquire(i);
        }
    }

    // Validates before any counter is read or written, so a mismatch leaves
    // both histograms untouched.
    private long[] checkedDestination(Histogram destination) {
        if (!config.equals(destination.config())) {
            throw new IllegalArgumentException(
                    "destination histogram has an incompatible configuration");
        }
        return destination.bucketsRef();
    }

    @Override
    public String toString() {
        return "AtomicHistogram(grouping_power=" + config.groupingPower()
                + ", max_value_power=" + config.maxValuePower() + ")";
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -B --no-transfer-progress -Dtest=AtomicHistogramTest test`
Expected: `Tests run: 8, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/systems/iop/h2histogram/AtomicHistogram.java \
        src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java
git commit -m "feat: add AtomicHistogram with concurrent recording and load

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `drain` and `drainInto`

**Files:**
- Modify: `src/main/java/systems/iop/h2histogram/AtomicHistogram.java`
- Modify: `src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java`

**Interfaces:**
- Consumes (from Task 1): the private fields `config` and `buckets`, and `private long[] checkedDestination(Histogram)`.
- Produces: `Histogram drain()`, `void drainInto(Histogram)`.

- [ ] **Step 1: Write the failing tests**

Add these methods inside `AtomicHistogramTest`, after `countsWrapModulo2To64`:

```java
    @Test
    void drainReturnsCountsAndResetsToZero() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        a.record(1L << 40, 6);

        Histogram expected = new Histogram(7, 64);
        expected.record(100, 4);
        expected.record(1L << 40, 6);

        assertEquals(expected, a.drain());
        assertEquals(new Histogram(7, 64), a.drain());
        assertEquals(new Histogram(7, 64), a.load());
    }

    @Test
    void loadDoesNotConsumeWhatDrainReturns() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(42, 3);
        a.load();
        assertEquals(3, a.drain().totalCount());
    }

    @Test
    void drainIntoOverwritesEveryDestinationBucket() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        Histogram destination = new Histogram(7, 64);
        destination.record(5000, 9);

        a.drainInto(destination);

        Histogram expected = new Histogram(7, 64);
        expected.record(100, 4);
        assertEquals(expected, destination);
        assertEquals(0, a.load().totalCount());
    }

    @Test
    void drainIntoRejectsMismatchedConfigAndChangesNothing() {
        AtomicHistogram a = new AtomicHistogram(7, 64);
        a.record(100, 4);
        Histogram destination = new Histogram(3, 64);
        destination.record(7, 2);
        Histogram before = Histogram.fromBuckets(3, 64, destination.bucketCounts());

        assertThrows(IllegalArgumentException.class, () -> a.drainInto(destination));
        assertEquals(before, destination);
        // The counters were not cleared by the failed drain.
        assertEquals(4, a.load().totalCount());
        assertThrows(NullPointerException.class, () -> a.drainInto(null));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -B --no-transfer-progress -Dtest=AtomicHistogramTest test`
Expected: `COMPILATION ERROR`, "cannot find symbol ... method drain()".

- [ ] **Step 3: Write the implementation**

In `AtomicHistogram.java`, add these two methods immediately after `loadInto`:

```java
    /**
     * Captures the current bucket values into a new {@link Histogram} and
     * resets this histogram to zero.
     */
    public Histogram drain() {
        Histogram snapshot = new Histogram(config);
        drainInto(snapshot);
        return snapshot;
    }

    /**
     * Captures the current bucket values into {@code destination}, reusing its
     * storage, and resets this histogram to zero. Every destination bucket is
     * replaced, including with zero. The caller must own {@code destination}
     * exclusively during the call.
     *
     * <p>Each bucket is captured and cleared in one atomic step, so every
     * recorded count is returned by exactly one drain: none is lost and none
     * is returned twice, whatever writers or other drains run concurrently. A
     * write concurrent with a drain lands in that drain or in a later one.
     *
     * @throws IllegalArgumentException if the configurations differ; neither
     *     histogram is changed
     */
    public void drainInto(Histogram destination) {
        long[] out = checkedDestination(destination);
        for (int i = 0; i < out.length; i++) {
            out[i] = buckets.getAndSet(i, 0L);
        }
    }
```

Then add one bullet to the class Javadoc's `<ul>`, after the `load` bullet:

```java
 *   <li>{@link #drain()} and {@link #drainInto(Histogram)} capture and clear
 *       each bucket in one atomic step. Every recorded count is returned by
 *       exactly one drain: none is lost and none is returned twice.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -B --no-transfer-progress -Dtest=AtomicHistogramTest test`
Expected: `Tests run: 12, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/systems/iop/h2histogram/AtomicHistogram.java \
        src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java
git commit -m "feat: add AtomicHistogram drain and drainInto

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Concurrency stress tests and user documentation

**Files:**
- Modify: `src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java`
- Modify: `README.md` (API table near line 99; new section before `## Unsigned values`)
- Modify: `src/main/java/systems/iop/h2histogram/package-info.java`

**Interfaces:**
- Consumes (from Tasks 1 and 2): `increment`, `load`, `drain`, `drainInto`; existing `Histogram.merge(Histogram)` and `Histogram.equals`.
- Produces: nothing new for later tasks.

These tests exercise code that already exists, so they are expected to pass on first run. Step 3 checks that the drain test can actually fail.

- [ ] **Step 1: Add the stress tests**

Replace the import block at the top of `AtomicHistogramTest.java` with:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
```

Add these members inside the class, after the last test:

```java
    // The two tests below are stress tests. A failure proves a bug; a pass
    // only fails to find one. They cannot prove the absence of a race.

    private static final int WRITERS = 8;
    private static final int PER_WRITER = 200_000;

    /** Half the values hit 16 hot buckets, half spread over the whole range. */
    private static long[][] writerInputs() {
        long[][] inputs = new long[WRITERS][PER_WRITER];
        for (int t = 0; t < WRITERS; t++) {
            Random rng = new Random(1000 + t);
            for (int i = 0; i < PER_WRITER; i++) {
                inputs[t][i] = (i & 1) == 0
                        ? rng.nextInt(16)
                        : rng.nextLong() >>> rng.nextInt(64);
            }
        }
        return inputs;
    }

    private static Histogram expectedFrom(long[][] inputs) {
        Histogram expected = new Histogram(7, 64);
        for (long[] perThread : inputs) {
            for (long v : perThread) {
                expected.increment(v);
            }
        }
        return expected;
    }

    /** Runs {@code body(threadIndex)} on {@code threads} threads released together. */
    private static void runTogether(int threads, IntConsumer body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int index = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS); // rethrows any failure from the thread
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void concurrentWritersAreAllCounted() throws Exception {
        long[][] inputs = writerInputs();
        AtomicHistogram a = new AtomicHistogram(7, 64);

        runTogether(WRITERS, t -> {
            for (long v : inputs[t]) {
                a.increment(v);
            }
        });

        assertEquals(expectedFrom(inputs), a.load());
    }

    @Test
    @Timeout(30)
    void everyCountIsReturnedByExactlyOneDrain() throws Exception {
        long[][] inputs = writerInputs();
        AtomicHistogram a = new AtomicHistogram(7, 64);
        AtomicBoolean writersDone = new AtomicBoolean(false);
        AtomicLong drains = new AtomicLong();
        Histogram[] accumulated = {new Histogram(7, 64)};

        Thread drainer = new Thread(() -> {
            Histogram scratch = new Histogram(7, 64);
            while (!writersDone.get()) {
                a.drainInto(scratch);
                accumulated[0] = accumulated[0].merge(scratch);
                drains.incrementAndGet();
            }
        });
        drainer.start();

        runTogether(WRITERS, t -> {
            for (long v : inputs[t]) {
                a.increment(v);
            }
        });
        writersDone.set(true);
        drainer.join(TimeUnit.SECONDS.toMillis(20));

        Histogram total = accumulated[0].merge(a.drain());
        assertTrue(drains.get() > 0, "the drainer never ran concurrently with writers");
        assertEquals(expectedFrom(inputs), total);
        assertEquals(new Histogram(7, 64), a.load());
    }
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `mvn -B --no-transfer-progress -Dtest=AtomicHistogramTest test`
Expected: `Tests run: 14, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 3: Check the drain test can fail**

Temporarily break the guarantee. In `AtomicHistogram.drainInto`, replace the loop body with a non-atomic read then clear:

```java
            out[i] = buckets.get(i);
            buckets.set(i, 0L);
```

Run: `mvn -B --no-transfer-progress -Dtest=AtomicHistogramTest#everyCountIsReturnedByExactlyOneDrain test`
Expected: `FAIL` with an `expected: <Histogram(...total_count=1600000)> but was: <...>` style mismatch, because increments landing between the read and the clear are lost. This is a race, so if it happens to pass, run it up to five times. If it never fails, stop and report that: the test is not exercising the race and needs more writers or more drains.

Restore the loop body to `out[i] = buckets.getAndSet(i, 0L);` and rerun Step 2 to confirm 14 tests pass. Confirm with `git diff src/main` that `AtomicHistogram.java` is unchanged from Task 2.

- [ ] **Step 4: Document the type in the README**

In `README.md`, add this row to the API overview table directly under the `Histogram` row:

```markdown
| `AtomicHistogram` | Lock-free concurrent recorder (crate's `AtomicHistogram`); `increment`, `record`, `load`, `loadInto`, `drain`, `drainInto`. Snapshot to a `Histogram` to query |
```

Insert this section immediately before the `## Unsigned values` heading:

````markdown
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
- If each thread can own its histogram, one plain `Histogram` per thread,
  merged when reporting, is faster. See [benchmarks](benchmarks/README.md).
````

- [ ] **Step 5: Mention the type in `package-info.java`**

In `src/main/java/systems/iop/h2histogram/package-info.java`, add this paragraph immediately before the closing ` */`:

```java
 *
 * <p>{@link systems.iop.h2histogram.Histogram} is not thread-safe. To record
 * from several threads into one shared instance use
 * {@link systems.iop.h2histogram.AtomicHistogram}, then snapshot it to a
 * {@code Histogram} to query.
```

- [ ] **Step 6: Run the full build**

Run: `mvn -B --no-transfer-progress verify`
Expected: `BUILD SUCCESS`. This also builds Javadoc, which fails on a broken `{@link}`.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/systems/iop/h2histogram/AtomicHistogramTest.java \
        src/main/java/systems/iop/h2histogram/package-info.java README.md
git commit -m "test: stress AtomicHistogram under concurrent writers and drains

Also documents the type in the README and package docs.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: JMH benchmark

**Files:**
- Create: `benchmarks/src/main/java/systems/iop/h2histogram/bench/AtomicRecordBenchmark.java`
- Modify: `benchmarks/README.md`

**Interfaces:**
- Consumes: `AtomicHistogram(Config)`, `AtomicHistogram.increment(long)`, `Histogram(Config)`, `Histogram.increment(long)`, `Config.totalBuckets()`, `Config.indexToLowerBound(int)`.
- Produces: JMH benchmarks `AtomicRecordBenchmark.sharedAtomic` and `AtomicRecordBenchmark.perThreadPlain`.

JMH's thread count is a run option, not a `@Param`, so the thread sweep is done with `-t` on the command line.

- [ ] **Step 1: Write the benchmark**

Create `benchmarks/src/main/java/systems/iop/h2histogram/bench/AtomicRecordBenchmark.java`:

```java
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
 * thread the two benchmarks price the atomic add against a plain add with no
 * contention. At higher counts {@code sharedAtomic} shows contention and false
 * sharing while {@code perThreadPlain} shows what writer ownership buys.
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
```

- [ ] **Step 2: Build the benchmark jar**

From the repository root:

```bash
mvn -B --no-transfer-progress install -DskipTests
cd benchmarks
mvn -B --no-transfer-progress package
```

Expected: `BUILD SUCCESS` twice, and `benchmarks/target/benchmarks.jar` exists.

- [ ] **Step 3: Smoke-test that both benchmarks run**

Run (from `benchmarks/`): `java -jar target/benchmarks.jar AtomicRecordBenchmark -t 2 -wi 1 -i 1 -w 1 -r 1 -p precision=gp3 -p spread=few`
Expected: a results table with two rows, `AtomicRecordBenchmark.perThreadPlain` and `AtomicRecordBenchmark.sharedAtomic`, each with a numeric `ns/op` score and no exception.

- [ ] **Step 4: Run the thread sweep**

Run (from `benchmarks/`), one after another, not in parallel, on an otherwise idle machine:

```bash
for t in 1 2 4 8; do
  java -jar target/benchmarks.jar AtomicRecordBenchmark -t $t -rf csv -rff atomic-t$t.csv
done
```

Expected: four CSV files, each with 8 rows (2 benchmarks x 2 precisions x 2 spreads). This takes about six minutes.

- [ ] **Step 5: Record the method and results in `benchmarks/README.md`**

Append this section to the end of `benchmarks/README.md`, replacing each `<...>` cell with the `Score` from the matching CSV row rounded to two decimals, and the environment line with the real values from `java -version`, `lscpu | grep 'Model name'` and `nproc`:

````markdown
## Concurrent recording: shared atomic versus per-thread

`AtomicRecordBenchmark` compares recording into one shared `AtomicHistogram`
against one plain `Histogram` per thread, at 1, 2, 4 and 8 threads.

```bash
java -jar target/benchmarks.jar AtomicRecordBenchmark -t 4
```

`spread=few` aims every thread at the same 64 buckets, the worst case for a
shared histogram. `spread=all` spreads writes over every bucket. Scores are
average ns per `increment`, per thread; lower is better.

Environment: `<CPU model>`, `<n>` hardware threads, `<java -version first line>`,
JMH 1.37, 3 x 1 s warmup, 5 x 1 s measurement, 1 fork.

| Precision | Spread | Threads | shared `AtomicHistogram` | per-thread `Histogram` |
|---|---|---:|---:|---:|
| gp3 | few | 1 | <...> | <...> |
| gp3 | few | 2 | <...> | <...> |
| gp3 | few | 4 | <...> | <...> |
| gp3 | few | 8 | <...> | <...> |
| gp3 | all | 1 | <...> | <...> |
| gp3 | all | 2 | <...> | <...> |
| gp3 | all | 4 | <...> | <...> |
| gp3 | all | 8 | <...> | <...> |
| gp7 | few | 1 | <...> | <...> |
| gp7 | few | 2 | <...> | <...> |
| gp7 | few | 4 | <...> | <...> |
| gp7 | few | 8 | <...> | <...> |
| gp7 | all | 1 | <...> | <...> |
| gp7 | all | 2 | <...> | <...> |
| gp7 | all | 4 | <...> | <...> |
| gp7 | all | 8 | <...> | <...> |

These are measurements from one host and one run. The one-thread rows price the
atomic add itself. The gap that opens at higher thread counts is contention and
false sharing between neighbouring counters, which per-thread ownership avoids
entirely. Use `AtomicHistogram` when writers must share an instance; prefer one
`Histogram` per writer, merged at report time, when they need not.
````

The `<...>` markers above are data to be filled from Step 4's output, not unfinished design. Do not commit the README with any `<...>` remaining; if a run failed, fix it and rerun rather than leaving a gap. Write the closing paragraph to match what the numbers show: if per-thread is not faster somewhere, say so instead of the sentence above.

- [ ] **Step 6: Commit**

Do not commit the CSV files.

```bash
cd ..
git add benchmarks/src/main/java/systems/iop/h2histogram/bench/AtomicRecordBenchmark.java \
        benchmarks/README.md
git status --short   # expect nothing else staged; atomic-t*.csv stay untracked
git commit -m "bench: compare shared AtomicHistogram with per-thread histograms

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
rm -f benchmarks/atomic-t*.csv
```

---

## Final verification

- [ ] From the repository root: `mvn -B --no-transfer-progress verify` prints `BUILD SUCCESS` with `AtomicHistogramTest` reporting 14 tests.
- [ ] `git diff main -- pom.xml benchmarks/pom.xml` prints nothing: no version changed.
- [ ] `git diff main -- src/main/java/systems/iop/h2histogram/Histogram.java` prints nothing: the existing public API is untouched.
- [ ] `grep -n '<\.\.\.>' benchmarks/README.md` prints nothing.
- [ ] `git log --oneline main..HEAD` shows the spec commit, this plan's commit, and four task commits.
