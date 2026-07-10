package com.iopsystems.h2histogram;

/**
 * Pairs a requested percentile with the {@link Bucket} it resolves to.
 *
 * @param percentile the requested percentile, in {@code [0.0, 1.0]}
 * @param bucket the bucket the percentile falls into
 */
public record PercentileResult(double percentile, Bucket bucket) {}
