# M4c stage 3: thermal segment streaming

This stage is stacked on M4c stage 2 PR #26, exact baseline commit
198a3850489260a5b097b61a2686f7bf22576c59.

## Change

The Android adapter no longer retains full temperature histories for
CPU, GPU and battery. Each thermal accumulator accepts samples directly
from the SQLite cursor, using the stage-1 exact scalar statistics
accumulator, and retains only the current continuous segment and the
longest completed segment. The two-pass least-squares regression over
the longest segment preserves the old numerical order of operations,
sensor identity rules, gap handling and first-longest tie breaking.

## Compatibility and limits

- Keep original DukoThermal.analyze as a reference implementation.
- Compare entire reports over 12,000+ readings, source changes,
  invalid values, non-monotonic timestamps, exact selected windows,
  short segments and empty windows.
- No SQLite schema migration, recorder lifecycle change, permissions,
  HUD change, sensor calibration or reporting-contract change.
- Still not constant-memory: a single uninterrupted thermal segment
  can contain every observation, and exact percentiles retain scalar
  values. Memory improvements are largest when there are many shorter
  segments. A future separate, mathematically validated regression
  accumulator can reduce the worst-case retained segment further.
- Temperature slopes are trends, never proof of causal throttling.
- Require current-head full JVM and Android API35 CI, optimized
  instrumentation, ABI, permission and release-style smoke checks.
- Keep draft/unmerged until all checks pass and integration reviewed.
- Physical Odin 2 Portal thermal and overlay behavior remain untested.
