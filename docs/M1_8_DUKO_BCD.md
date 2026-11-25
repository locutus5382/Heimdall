# Heimdall M1.8 — DUKO Advanced Analytics (B → C → D)

## Version and protection
- Base: **verified** `950cfc606aeca80e4db04f265632b7bf6fdec423`,
  `0.8.0-percore-psi-alpha`, 96/96 standard and 96/96 optimized API 35 tests.
- Development branch: `feature/m18-duko-minute-residency-events`.
- Development version: `0.8.1-duko-timelines-alpha`, versionCode 10.
- The tested 0.8.0 APK/commit is **not changed**.
- M1.8 is not RC1. Still requires actual Odin hardware and telemetry validation.

## B — One-minute statistical windows

Read-only, retrospective computation from the existing SQLite v7 rows in
**one analysis cursor**. Every 60-second block starts at the selected
monotonic analysis window's beginning. Each is `[start,end)` except the
recording's final endpoint. The short tail is explicitly labeled PARTIAL.

Per-block: CPU/GPU load and clocks, battery/CPU/GPU temperatures, available
memory, exact median/P95/mean/time-mean/peak/sample standard deviation,
sampling coverage/evidence/warnings, battery-terminal discharge estimates
and existing CPU/memory PSI avg10 observations. Missing intervals contain
empty/insufficient evidence, **not zeros or interpolations**.

Limit is the earliest **360** displayed minute blocks; long sessions report
the actual total and visible truncation warning. To evaluate later portions,
use an explicit selected analysis window. No recorder or database changes.

## C — CPU-policy clock residency

Each actually recorded `policyN` cpufreq clock stream is processed with the
existing `ClockResidencyAccumulator`. Readings in an interval are credited
using the preceding sampled MHz only if both endpoints are valid, monotonic
and within the measured cadence budget. Missing policy reads, broken ticks
and missing topology are **not fabricated**.

- 400 MHz sample-frequency bands through 4,000 MHz, with an overflow band.
- Per-policy qualified time, unknown time, transitions, largest sampled rise
  and drop, top occupied bands, and per-policy evidence grade.
- Use saved `relatedCpus` if present; do not assume Odin's policy0/3/7.
- Sampling snapshots do **not** establish per-core CPU frequency, game
  frame rate or why the kernel chose a policy ceiling.
- Existing per-core busiest-activity calculation stays separate from
  frequency residency.

## D — Unified event timeline

Computed from recorded timestamps; never written to a new events table:
- True recording start/end (or interruption) only for complete recording
  analysis. When a user selects a *slice* of a session, mark
  ANALYSIS_WINDOW_BEGIN / ANALYSIS_WINDOW_END instead; do not misrepresent
  the slice boundary as actual recording start/stop. Session interruption
  remains in the integrity report.
- Observed CPU-policy cap transitions with their recorded active/hardware
  ceilings; **no ClusterTune/thermal causality attribution**.
- Heuristic sustained signals: overall CPU load ≥95% for ≥120s, GPU load
  ≥95% for ≥120s, battery ≥42°C for ≥15s, Android thermal status
  ≥MODERATE for ≥10s, system CPU PSI some avg10 ≥10% for ≥20s,
  and system memory PSI some avg10 ≥10% for ≥20s.
- Adjacent valid observations and bounded sampling gaps required; missing
  sensors, pauses and nonmonotonic clocks cannot extend an event.
- Timeline sorted by monotonic timestamp, with duration and category;
  a 250-event UI/report cap plus explicit dropped-count notice prevents
  runaway Compose work. **Thresholds are experimental**, pending Odin tests.

### UI integration

Existing Sessions → DUKO Analysis hosts three touch-accessible tabs:
**MINUTES**, **CPU POLICIES** and **EVENTS**. Minute selection presents
time-local medians/P95, means, SD, coverage, energy evidence and PSI.
Residency lists qualified/unknown duration and top bands. Events provide
an illustrative position bar and a filterable, accessible timestamped list
with pages of 20 events. Existing full-session charts and A/B comparisons
remain in place.

## Validation gates

1. New pure Kotlin unit tests for minute boundaries, missing blocks,
   truncated long sessions, per-policy gaps and transitions, sustained
   events, pressure/thermal thresholds and cap-event merge.
2. Android test loads saved v7 evidence and verifies retroactive minute
   summaries, per-policy residency and event integration without any
   hardware sysfs override.
3. Existing Android API 35 UI/recording/migration tests run together with
   new touch-navigation and selected-window boundary regression checks,
   including 0.8.0's 96 standard and 96 optimized tests; updated
   analysis-version assertions must not weaken checks.
4. Verify both normal and leanDebug R8 instrumentation variants on the
   **same exact commit**. Production release signing remains separate.
5. Odin test results still needed for sensor availability, live HUD,
   recorder overhead and interpretation of thresholds.

No feature completion claim before CI. No implication that 0.8.1's event
heuristics were calibrated to an Odin 2 Portal.
