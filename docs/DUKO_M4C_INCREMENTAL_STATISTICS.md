# DUKO M4c — incremental metric analysis (stage 1)

## Goal and status

M4b made SQLite selected-window reads index-bounded. M4c begins eliminating
full-session Android heap pressure while preserving exact DUKO conclusions,
including nearest-rank P5/P95/P99, median, sample standard deviation, and
time-weighted coverage. This change is **stage 1**, not a claim of
constant-memory analysis.

## Implementation

- New Android-free DukoStatisticsAccumulator accepts one observation at a
  time, in persisted **sample ID** order. It preserves all D2/M3a rules:
  no reversing timestamps, filling missing ticks, bridging changed source
  identities, or extending integration across an excessive gap.
- Android's DukoSessionAnalyzer now pre-reads monotonic session extrema
  using the existing v6 elapsed-time index, including for full-session calls.
  Empty and invalid selected windows retain the M4a behavior.
- Instead of building **11 independent lists of DukoObservation objects**,
  the adapter feeds all 11 metric accumulators directly from the SQLite cursor.
  Only the three thermal sensor series and battery electrical points still
  retain their full observation objects for existing trend/energy algorithms.
- Accumulators retain **valid numeric Double values** to calculate exactly
  the same median and nearest-rank quantiles as before. Each accumulator
  sorts its values in place on finalization; no second sorted copy is needed.
- No database schema change, recorder or HUD changes, network permission,
  device-specific assumption, battery calibration assumption, or release R8
  alteration. The DUKO report contract remains version 2.

## Verification

DukoStatisticsAccumulatorTest compares *every field* of the new result with
the existing reference DukoStatistics.analyze on long recordings, windows,
missing/nonfinite values, source changes, corrupted ordering and sparse input.
Existing Android DUKO integration and 12,000-sample M4b tests exercise the
new streaming adapter on both unminified and R8-optimized QA builds.

## Remaining M4c work

Peak memory is reduced, but not bounded independent of session length.
Exact order statistics still require retained scalar values here. Thermal
trend-fitting and battery-discharge integration still retain their per-tick
lists. Later stages may move thermal/energy to online accumulators and use
disk-backed exact selection / explicit approximation contracts for quantiles.
Do not claim constant memory or performance gains on the Odin 2 Portal until
physical benchmarking.
