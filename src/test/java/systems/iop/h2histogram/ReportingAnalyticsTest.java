package systems.iop.h2histogram;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ReportingAnalyticsTest {
    private Histogram fixture() {
        Histogram h = new Histogram(7, 32);
        h.record(3, 2);
        h.record(300, 3);
        h.record(9999, 5);
        return h;
    }

    @Test void cumulativeConstructionRejectsOverflowInsteadOfWrapping() {
        Histogram h = new Histogram(2, 8);
        h.record(1, -1L);
        h.record(2, 1);
        assertThrows(ArithmeticException.class, h::toCumulative);
        assertThrows(ArithmeticException.class, () -> h.toSparse().toCumulative());
    }

    @Test void checkedAdditionPreservesDestinationOnLateOverflowAndMismatch() {
        for (int position : new int[] {0, 1, 8, 27}) {
            Histogram dst = new Histogram(2, 8);
            dst.bucketsRef()[position] = -1L;
            Histogram add = new Histogram(2, 8);
            java.util.Arrays.fill(add.bucketsRef(), 1);
            Histogram before = Histogram.fromBuckets(2, 8, dst.bucketCounts());
            assertThrows(ArithmeticException.class, () -> dst.checkedAddAssign(add));
            assertEquals(before, dst);
            assertThrows(IllegalArgumentException.class,
                    () -> dst.checkedAddAssign(new Histogram(1, 8)));
            assertEquals(before, dst);
        }
        Histogram h = fixture();
        Histogram expected = h.merge(h);
        h.checkedAddAssign(h);
        assertEquals(expected, h);
    }

    @Test void checkedSumOwnsResultAndValidatesEveryConfigBeforeOverflow() {
        Histogram h = fixture();
        Histogram before = Histogram.fromBuckets(7, 32, h.bucketCounts());
        assertEquals(h.merge(h), Histogram.checkedSum(List.of(h, h)));
        Histogram one = Histogram.checkedSum(List.of(h));
        one.increment(0);
        assertEquals(before, h);
        assertThrows(IllegalArgumentException.class, () -> Histogram.checkedSum(List.of()));
        Histogram huge = new Histogram(7, 32);
        huge.record(0, -1L);
        assertThrows(IllegalArgumentException.class,
                () -> Histogram.checkedSum(List.of(huge, huge, new Histogram(1, 8))));
        assertThrows(ArithmeticException.class, () -> Histogram.checkedSum(List.of(huge, huge)));
        assertEquals(-1L, huge.totalCount());
    }

    @Test void resetSnapshotAndDrainReuseStorageAndRejectSelfDrain() {
        Histogram source = fixture();
        Histogram before = Histogram.checkedSum(List.of(source));
        Histogram dst = new Histogram(source.config());
        long[] sourceStorage = source.bucketsRef();
        long[] dstStorage = dst.bucketsRef();
        dst.increment(0);
        source.snapshotInto(dst);
        assertEquals(before, dst);
        assertSame(dstStorage, dst.bucketsRef());
        source.snapshotInto(source);
        assertEquals(before, source);
        assertThrows(IllegalArgumentException.class, () -> source.drainInto(source));
        assertThrows(IllegalArgumentException.class, () -> source.drainInto(new Histogram(1, 8)));
        assertEquals(before, source);
        source.drainInto(dst);
        assertEquals(before, dst);
        assertEquals(0, source.totalCount());
        assertSame(sourceStorage, source.bucketsRef());
        dst.reset();
        assertSame(dstStorage, dst.bucketsRef());
        assertEquals(0, dst.totalCount());
    }

    @Test void queriesPreserveOrderDuplicatesEmptyAndUnsignedCounts() {
        Histogram h = fixture();
        double[] ps = {1, 0, .5, .9, .5};
        Bucket[] dense = new Bucket[ps.length + 1];
        Bucket[] sparse = new Bucket[ps.length + 1];
        Bucket[] cumulative = new Bucket[ps.length + 1];
        assertEquals(ps.length, h.percentilesInto(ps, dense));
        assertEquals(ps.length, h.toSparse().percentilesInto(ps, sparse));
        assertEquals(ps.length, h.toCumulative().percentilesInto(ps, cumulative));
        assertArrayEquals(dense, sparse);
        assertArrayEquals(dense, cumulative);
        for (int i = 0; i < ps.length; i++) {
            assertEquals(h.percentiles(ps).get(i).bucket(), dense[i]);
            assertEquals(h.percentile(ps[i]), h.toSparse().percentile(ps[i]));
            assertEquals(h.percentile(ps[i]), h.toCumulative().percentile(ps[i]));
        }
        Bucket sentinel = dense[0];
        assertThrows(IllegalArgumentException.class,
                () -> h.percentilesInto(new double[] {.5, Double.NaN}, dense));
        assertEquals(sentinel, dense[0]);
        assertThrows(IllegalArgumentException.class, () -> h.percentilesInto(ps, new Bucket[1]));
        h.reset();
        assertEquals(0, h.percentilesInto(ps, dense));
        assertEquals(sentinel, dense[0]);
        assertTrue(h.toSparse().percentile(.5).isEmpty());
        h.record(10, Long.MIN_VALUE);
        h.record(20, Long.MAX_VALUE);
        assertEquals(20, h.percentile(1).orElseThrow().start());
        assertEquals(h.percentiles(ps), h.toSparse().percentiles(ps));
        assertEquals(h.percentiles(ps), h.toCumulative().percentiles(ps));
    }

    @Test void nativeTransformsMatchDenseCountsAndRecomputeCoarseMean() {
        Random random = new Random(42);
        for (int round = 0; round < 20; round++) {
            Histogram a = new Histogram(7, 32);
            Histogram b = new Histogram(7, 32);
            for (int i = 0; i < 100; i++) {
                a.record(random.nextInt(100000), random.nextInt(20));
                b.record(random.nextInt(100000), random.nextInt(20));
            }
            assertEquals(a.merge(b).toSparse(), a.toSparse().merge(b.toSparse()));
            assertEquals(a.merge(b).toCumulative(), a.toCumulative().merge(b.toCumulative()));
            assertEquals(a.toSparse(), a.toCumulative().toSparse());
            for (int gp : new int[] {0, 3, 6}) {
                assertEquals(a.downsample(gp).toSparse(), a.toSparse().downsample(gp));
                CumulativeHistogram coarse = a.toCumulative().downsample(gp);
                assertEquals(a.downsample(gp).toCumulative(), coarse);
                assertEquals(a.downsample(gp).toCumulative().mean(), coarse.mean());
            }
        }
        Histogram h = fixture();
        assertThrows(IllegalArgumentException.class, () -> h.toSparse().downsample(7));
        assertThrows(IllegalArgumentException.class,
                () -> h.toCumulative().merge(new Histogram(1, 8).toCumulative()));
    }

    @Test void transformsCheckUnsignedOverflowAndAcceptUnsignedImports() {
        Config config = new Config(2, 8);
        SparseHistogram huge = SparseHistogram.fromParts(config, new int[] {8}, new long[] {-1L});
        assertThrows(ArithmeticException.class, () -> huge.merge(huge));
        assertThrows(ArithmeticException.class, () -> huge.toCumulative().merge(huge.toCumulative()));
        SparseHistogram separate = SparseHistogram.fromParts(config,
                new int[] {8, 9}, new long[] {-1L, 1});
        assertThrows(ArithmeticException.class, () -> separate.downsample(1));
        assertThrows(ArithmeticException.class, separate::toCumulative);
        assertEquals(-1L, huge.toCumulative().totalCount());
        assertThrows(IllegalArgumentException.class,
                () -> SparseHistogram.fromParts(config, new int[] {2, 2}, new long[] {1, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> CumulativeHistogram.fromParts(config, new int[] {1, 2}, new long[] {-1L, 1}));
        // Previously accepted zero sparse entries and equal cumulative prefixes stay accepted.
        SparseHistogram zero = SparseHistogram.fromParts(config, new int[] {0, 3}, new long[] {0, 2});
        assertEquals(zero.toDense().toCumulative(), zero.toCumulative());
        CumulativeHistogram plateau = CumulativeHistogram.fromParts(config,
                new int[] {1, 2}, new long[] {2, 2});
        assertEquals(plateau.toDense().toSparse(), plateau.toSparse());
    }

    @Test void importedArraysAndReadOnlyAccessorsCannotMutateSnapshots() {
        Config config = new Config(2, 8);
        int[] indices = {1, 2};
        long[] counts = {2, 3};
        SparseHistogram sparse = SparseHistogram.fromParts(config, indices, counts);
        CumulativeHistogram cumulative = sparse.toCumulative();
        indices[0] = 10;
        counts[0] = 99;
        sparse.index()[0] = 11;
        sparse.count()[0] = 100;
        cumulative.index()[0] = 12;
        cumulative.count()[0] = 101;
        assertArrayEquals(new int[] {1, 2}, sparse.index());
        assertArrayEquals(new long[] {2, 3}, sparse.count());
        assertEquals(5, cumulative.totalCount());
        assertEquals(1.6, cumulative.mean().orElseThrow(), 1e-12);
        assertEquals(sparse, cumulative.toSparse());
    }

    @Test void emptyAndInvalidQueriesHaveConsistentBufferContracts() {
        Histogram h = new Histogram(2, 8);
        Bucket marker = new Bucket(1, 1, 1);
        Bucket[] out = {marker, marker};
        double[] ps = {0, 1};
        assertEquals(0, h.toSparse().percentilesInto(ps, out));
        assertEquals(0, h.toCumulative().percentilesInto(ps, out));
        assertArrayEquals(new Bucket[] {marker, marker}, out);
        assertThrows(IllegalArgumentException.class,
                () -> h.toSparse().percentilesInto(new double[] {0, Double.NaN}, out));
        assertThrows(IllegalArgumentException.class,
                () -> h.toCumulative().percentilesInto(new double[] {0, Double.NaN}, out));
        assertThrows(IllegalArgumentException.class,
                () -> h.toSparse().percentilesInto(ps, new Bucket[0]));
        assertThrows(IllegalArgumentException.class,
                () -> h.toCumulative().percentilesInto(ps, new Bucket[0]));
        assertThrows(IllegalArgumentException.class, () -> h.percentile(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> h.toSparse().percentile(-.1));
        assertThrows(IllegalArgumentException.class, () -> h.toCumulative().percentile(1.1));
        assertEquals(h.toSparse(), h.toSparse().merge(h.toSparse()));
        assertEquals(h.downsample(1).toCumulative(), h.toCumulative().downsample(1));
    }

    @Test void nearMaximumPercentileRankDoesNotRoundAboveTotal() {
        Histogram h = new Histogram(2, 8);
        h.record(1, -2L);
        assertEquals(1, h.percentile(1).orElseThrow().start());
        assertEquals(h.percentile(1), h.toSparse().percentile(1));
        assertEquals(h.percentile(1), h.toCumulative().percentile(1));
        SparseHistogram zeroTail = SparseHistogram.fromParts(h.config(),
                new int[] {1, 2}, new long[] {-2L, 0});
        assertEquals(h.percentile(1), zeroTail.percentile(1));
    }

    @Test void queriesRejectUnrepresentableTotalsBeforeWriting() {
        Histogram h = new Histogram(2, 8);
        h.record(1, -1L);
        h.record(2, 1);
        Bucket marker = new Bucket(1, 1, 1);
        Bucket[] out = {marker};
        assertThrows(ArithmeticException.class, () -> h.percentile(.5));
        assertThrows(ArithmeticException.class, () -> h.toSparse().percentile(.5));
        assertThrows(ArithmeticException.class, () -> h.percentilesInto(new double[] {.5}, out));
        assertThrows(ArithmeticException.class,
                () -> h.toSparse().percentilesInto(new double[] {.5}, out));
        assertSame(marker, out[0]);
    }

    @Test void emptyRequestsDoNotRequireARepresentableTotal() {
        Histogram h = new Histogram(2, 8);
        h.record(1, -1L);
        h.record(2, 1);
        assertEquals(List.of(), h.percentiles());
        assertEquals(List.of(), h.toSparse().percentiles());
        assertEquals(0, h.percentilesInto(new double[0], new Bucket[0]));
        assertEquals(0, h.toSparse().percentilesInto(new double[0], new Bucket[0]));
    }

    @Test void percentileOneUsesExactUnsignedTotalAcrossEveryQueryPath() {
        for (long total : new long[] {(1L << 53) + 1, Long.MIN_VALUE + 1, -2L, -1L}) {
            Histogram dense = new Histogram(2, 8);
            dense.record(0, total - 1);
            dense.record(1, 1);
            SparseHistogram sparse = dense.toSparse();
            CumulativeHistogram cumulative = dense.toCumulative();
            double[] ps = {1, 0, 1};
            Bucket[] denseOut = new Bucket[3];
            Bucket[] sparseOut = new Bucket[3];
            Bucket[] cumulativeOut = new Bucket[3];
            dense.percentilesInto(ps, denseOut);
            sparse.percentilesInto(ps, sparseOut);
            cumulative.percentilesInto(ps, cumulativeOut);
            Bucket expected = new Bucket(1, 1, 1);
            assertAll("total=" + Long.toUnsignedString(total),
                    () -> assertEquals(expected, dense.percentile(1).orElseThrow()),
                    () -> assertEquals(expected, sparse.percentile(1).orElseThrow()),
                    () -> assertEquals(expected, cumulative.percentile(1).orElseThrow()),
                    () -> assertEquals(expected, dense.percentiles(ps).get(0).bucket()),
                    () -> assertEquals(expected, sparse.percentiles(ps).get(0).bucket()),
                    () -> assertEquals(expected, cumulative.percentiles(ps).get(0).bucket()),
                    () -> assertEquals(expected, denseOut[0]),
                    () -> assertEquals(expected, sparseOut[0]),
                    () -> assertEquals(expected, cumulativeOut[0]));
        }
    }

    @Test void sparseBatchMatchesScalarForManyUnorderedDuplicatesAndImportedZeros() {
        Histogram dense = new Histogram(7, 32);
        Random random = new Random(8371);
        for (int i = 0; i < 500; i++) {
            dense.record(random.nextInt(100000), 1 + random.nextInt(9));
        }
        int[] indices = new int[dense.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        // Explicit zeros between and after observations must not affect the sorted scan.
        SparseHistogram sparse = SparseHistogram.fromParts(dense.config(), indices, dense.bucketCounts());
        double[] ps = new double[200];
        for (int i = 0; i < ps.length; i++) {
            ps[i] = random.nextInt(21) / 20.0;
        }
        ps[0] = -0.0;
        ps[1] = 0.0;
        double[] before = ps.clone();
        List<PercentileResult> results = sparse.percentiles(ps);
        assertArrayEquals(before, ps);
        assertEquals(dense.percentiles(ps), results);
        for (int i = 0; i < ps.length; i++) {
            assertEquals(sparse.percentile(ps[i]).orElseThrow(), results.get(i).bucket());
        }
    }
}
