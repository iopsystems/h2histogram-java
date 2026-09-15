# Changelog

## Unreleased

Adds reusable reset/snapshot/drain, checked aggregation, caller-output queries,
and native sparse/cumulative analytics without recording metadata writes.

### Compatibility and Rust interoperability

- Sparse imports validate all supplied indices, then omit zero counts. `size`,
  `index`, `count` and equality therefore reflect normalized occupied buckets,
  not positional alignment with input arrays. Preserve external column alignment
  separately. Rust may reject zero sparse entries instead of normalizing them.
- Percentile reports and cumulative construction now reject totals/prefixes that
  exceed unsigned 64-bit capacity with `ArithmeticException`. Individually valid
  u64 buckets imported from Rust can have such a total. Java does not widen its
  report accumulator to Rust's u128; callers should handle this exception.
- Existing dense recording, merge, downsample and `totalCount` retain modulo-2^64
  arithmetic. A wrapping `totalCount` is not a validity check for a report.
- New `percentilesInto` returns the number written. Empty histograms return zero
  and leave all slots untouched; read only the returned prefix. This differs from
  Rust's API that fills optional result slots for an empty histogram. Invalid
  capacity/requests are both rejected before writes; error precedence is not a
  cross-language guarantee.

Snapshot column accessors remain defensive copies. Fixed-size Java arrays require
no compaction API. Snapshot/drain is not atomic and requires exclusive access.
