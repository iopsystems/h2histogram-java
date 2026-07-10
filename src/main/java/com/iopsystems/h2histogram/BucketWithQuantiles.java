package com.iopsystems.h2histogram;

/**
 * Bundles a bucket with its quantile span.
 *
 * <p>{@code lowerQuantile} is the fraction of observations strictly before
 * this bucket and {@code upperQuantile} the fraction at or before it, both in
 * {@code [0.0, 1.0]}.
 *
 * @param bucket the bucket, with its individual (non-cumulative) count
 * @param lowerQuantile the fraction of observations strictly before this bucket
 * @param upperQuantile the fraction of observations at or before this bucket
 */
public record BucketWithQuantiles(Bucket bucket, double lowerQuantile, double upperQuantile) {}
