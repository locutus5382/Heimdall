# DUKO M4c stage 2 — incremental battery-energy evidence

## Scope

Stacked on CI-green M4c stage 1, PR #25, commit 620419d.

The Android DUKO adapter previously retained one DukoBatteryPoint
for each sampled tick and then transformed that list into another
DukoObservation list inside DukoEnergy.analyze.

DukoEnergyAccumulator now accepts each battery reading directly from
the SQLite cursor and retains only the scalar watts required by exact
order statistics. This removes the entire battery point history from
the analyzer. No database row is modified or discarded.

## Evidence and numeric compatibility

- Keep the original list-based DukoEnergy.analyze as an independent
  reference implementation and compare complete report objects.
- Preserve original insertion-order evidence, out-of-order timestamps,
  source/provenance changes, missing values and selected-window bounds.
- Preserve charging and bypass exclusion rules, raw current sign,
  unknown and contradictory battery status handling, wider-pack voltage
  warnings, all per-status counters and exact energy/percentile results.
- Retain strict battery-terminal power labeling; this is not a claim
  about game, wall or device input power.
- No session confidence changes and no assumption that OEM battery
  telemetry is physically calibrated.

## Tests and limits

Differential JVM tests compare the new path with the old path over
more than 12,000 deliberately irregular electrical samples, an exact
selected window, out-of-order timestamps, invalid/missing readings,
contradictory battery statuses, and a constant 8 W reference.

This is not constant-memory processing: exact median/percentile values
still consume O(valid readings) scalar Double storage. Three thermal
temperature trend histories remain in memory. A separate stage should
stream the thermal regression segments, with old/new report parity
before removing the thermal arrays.

## Acceptance gate

Require all pure engine tests and Debug/lean/release/releaseQa builds,
release AAB, DEX ABI and offline manifest audit; normal and optimized
Android API 35 instrumentation; and optimized QA/releaseQa launch smoke.
No release claim, draft PR only, no automatic merge. Odin hardware
sensor accuracy and end-user HUD acceptance remain unvalidated.
