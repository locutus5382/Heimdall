# M5a — evidence-gated A/B comparison foundation

Stacked on CI-green M4c thermal streaming PR #27, commit
657bf9fcf22ac5e48e1a1da19fb7befcbf81d02b.

## What this stage adds

Pure Kotlin `DukoComparison` compares two already-computed, immutable
session/window metric summaries. It returns per-metric time-weighted
means, signed B-minus-A differences, optional percentage changes and
explicit quality/evidence warnings. It never invents missing metrics,
FPS, device input power, game performance or a causal winner.

- A and B must be distinct sessions; interrupted/recovery sessions
  and explicitly different workloads cannot produce numeric deltas.
- Both metric summaries must belong to their declared analysis windows,
  have at least qualified evidence and valid time-weighted means.
- Percent change is undefined at zero baseline rather than infinity.
- Differing driver, emulator, performance, fan and ClusterTune labels
  are warnings, not automatic causal attribution. Matching manual
  labels do not prove equivalent workloads or device state.
- A short/long duration mismatch triggers caution; missing/unknown
  workload and unverified tags remain explicitly qualified.
- No raw samples, SQL schema, hardware controls, permissions, or HUD
  lifecycle are changed.

## Next integration milestone

Wire this engine to two user-selected completed DUKO session reports
with bounded SQLite reads, display a concise comparison table with
coverage and source qualifications, then add Option B comparison-only
DUKO export. Recheck evidence confidence against real Odin 2 Portal
recordings before claiming performance/energy conclusions.

## Gates

Require exact-current-head JVM tests and complete Android build,
manifest/ABI, optimized instrumentation and emulator launch checks.
Remain draft/unmerged until review. Physical Odin sensor/overlay
acceptance remains unvalidated.
