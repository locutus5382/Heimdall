# DUKO M4b — large-session indexed time-window queries

## Motivation

In M4a, selected-window analysis avoided allocating the full recording in
Kotlin, but SQLite still had only `samples(session_id,id)`. For a six-hour
session, a one-minute selection can require scanning thousands of unrelated
samples before evaluating the elapsed-time predicate.

## Implementation

- Database schema v6 adds `samples_session_elapsed_idx` with columns
  `(session_id,elapsed_ms,id)`. New databases create it on initial setup.
  Existing v1–v5 databases gain it through the transactional, additive
  `onUpgrade` path without rewriting any row, sample ID, original timestamp,
  electrical reading, or DUKO session tag. Database filename remains
  `heimdall_v1.db` for backward-compatible continuity.
- M4a's **selected-window** SQL uses `INDEXED BY` on that index and bounds
  the search by `session_id` and both inclusive `elapsed_ms` limits.
  The MIN/MAX boundary check can also use the index.
- Keep `ORDER BY id` even when this induces a temporary SQLite sort on
  selected results. Ordering by elapsed time would conceal non-monotonic
  sample evidence, which must continue to degrade confidence.
- Full-session analysis, storage writes, imports/exports, offline permissions,
  recorder lifecycle and current DUKO numerical semantics are unchanged.

## Costs and limitations

The new index consumes additional persistent storage and incurs a small
write/index-maintenance cost for each new sample. Initial index creation
during upgrade can take longer on databases with very large recording
histories. This is deliberate in exchange for bounded range reads, and
must be measured on the Odin 2 Portal before claiming device-level speed
or energy savings. This milestone does **not** stream/aggregate all 11
metrics in constant memory; an extremely long full-session analysis still
materializes its arrays, reserved for later M4c architecture work.

## Reproducible synthetic benchmark

Run from repo root:

```bash
python3 scripts/bench_duko_window_sql.py --samples 50000 --window-samples 100 --repetitions 15
```

This runs a deterministic SQLite-in-memory microbenchmark using host Python,
not Android SQLite or real device telemetry. It reports plans, median query
time, identical result rows, and a measured ratio. Timings are diagnostic,
**not CI thresholds**, and they must never be presented as physical Odin
performance.

## Android integration acceptance

1. v5→v6 fixture verifies unchanged historical IDs, insertion order and
   raw current/voltage fields, plus SQLite integrity.
2. A 12,000-sample (synthetic six-hour-forty-minute) recording verifies the
   selected query plan uses the new elapsed index, the adapter analyzes only
   41 requested samples, and the original database remains unchanged.
3. Existing v1/v3/v4 migration and production-style recorder tests check
   historical contract compatibility against schema v6.

Keep the PR draft until Android API35 normal/R8 QA instrumentation and
release-style smoke all pass on the exact current head. Physical Odin
validation is still outstanding.
