# DUKO M3c — Session-level confidence and battery calibration

**Scope:** This milestone adds a conclusion-level assessment without changing the
raw SQLite v5 evidence, numerical integration, recording lifecycle, permissions,
networking, or production R8 behavior.

## Two distinct claims

1. **Energy calculation evidence** (`DukoEnergyReport.evidence`) measures
   internal quality of the reported battery current × voltage integration:
   time coverage, status consistency, missing values, sensor changes, and
   electrical reading plausibility. `SUPPORTED` here is *not* a calibration
   certificate or total-device power measurement.
2. **Battery-terminal conclusion** (`DukoConfidenceReport.batteryTerminalConclusion`)
   additionally accounts for **session outcome** and **independent device
   calibration**. The Android adapter always uses `NOT_VALIDATED`. Thus a
   generic handset's apparent battery-terminal Wh remains at most `QUALIFIED`
   until a real device-specific procedure validates the current/voltage
   representation. This does NOT convert it into game, whole-system, wall, or
   charger-input power.

## Session outcome policy

- `COMPLETED`: preserve each underlying supported, qualified, or insufficient
  metric grade; battery-terminal conclusions remain qualified while calibration
  has not been independently validated.
- `INTERRUPTED`: preserve numeric measurements and their original scores for
  audit, but cap `SUPPORTED` *conclusions* at `QUALIFIED`. Do not silently
  interpret an interrupted run as a full planned workload.
- `NEEDS_RECOVERY`: mark conclusions `INSUFFICIENT`; retain raw evidence.

No opaque global score: a missing GPU sensor does not change a valid CPU-load
grade. Thermal trend conclusions are separately capped by session outcome.
Reason codes are stable and carried with the new `confidence` report property.

## Validation

Pure JVM tests cover completed, interrupted, recovery-required, independently
validated (engine-only test seam), not-validated, missing electrical data,
absent GPU data, and determinism. Android instrumentation verifies SQLite
`end_reason` and v5 battery status reach the confidence report. No Android
runtime path sets `INDEPENDENTLY_VALIDATED` or claims that the Odin 2 Portal
has been calibrated.

**Hardware acceptance still pending:** actual battery gauge/current polarity,
pack-cell voltage scaling, bypass/charging behavior, and Odin 2 Portal field
tests. Do not use this report for game FPS or electricity-at-the-wall claims.
