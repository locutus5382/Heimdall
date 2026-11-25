# HEIMDALL

**We analyze how the handheld behaves, not how many frames the game renders.**

Heimdall is an Android-native, offline, read-only telemetry and DUKO analysis
app for handhelds. It has one foreground recorder Service, one sampler, one
private SQLite database and one optional Canvas HUD. It does **not** measure
FPS, control clocks/fans, require root, ask for an account, or send recordings
to a server. Independent of the older CoreMetrics package.

## Current development state — Phase 1 Odin pretest, 25 November 2026

Current test candidate: **v0.8.7-odin-test-candidate** (code 16),
branch `feature/phase1-v087-odin-test-candidate`, stacked on exact-head
CI-green v0.8.6 `77e5131bb04470238dfd47a21a020e887f163117`.
See [v0.8.7 test release notes](docs/PHASE1_V087_ODIN_TEST_RELEASE_NOTES.md).
The tested baseline v0.8.0 `950cfc606aeca80e4db04f265632b7bf6fdec423`
remains untouched. **This is not RC1 or a device-tested release.**

Phase 1 improves HUD failure isolation, ownership-safe overlay removal,
static sensor I/O caching, Canvas failure coalescing, and read-only verification of the latest saved
session's SQLite sample count. See
[the 27-case Odin acceptance matrix](docs/PHASE1_V086_ODIN_PRETEST.md).
Everything in the physical acceptance matrix is NOT TESTED until the
Odin 2 Portal provides actual evidence.
### Working features in source

- **Dashboard:** actual recorder Start, Stop & Save, status and persisted
  sample count, filtered CPU/GPU/battery/thermal/memory readings and the exact
  finished-session review path. Missing sensors display as unavailable.
- **Quick Settings + notification:** multiple command surfaces for the
  **same** recorder/service and recording status; no separate sampling loop.
- **Bifrost UI:** touch-first Dashboard / Sessions / Compare, contextual
  Setup / HUD Studio / Run Picker / Diagnostics blades; optional controller
  shortcuts must not steal text-entry focus.
- **HUD:** one permission-gated Canvas overlay with Pill, Card, Ribbon and
  Spine layouts, configurable metrics, docking, scale, opacity, touch lock
  and a renderer-backed Studio preview. Physical game overlay still untested.
- **Recorded evidence:** SQLite WAL, **schema v7**, durable STOP and
  interruption safeguards, read-only session history, on-demand analysis,
  recorded timestamps and selected-session CSV export. Existing data must
  not be deleted for testing.
- **DUKO:** offline session integrity/coverage and per-metric evidence,
  clock/thermal/frequency-cap observations, paired A/B comparison with
  independently bounded monotonic time windows, local TXT/CSV exports and
  missing-evidence warnings. A/B findings do not prove game causality.
- **Test conditions:** user-declared game, emulator, driver, fan, performance
  mode, ClusterTune and notes, marked as reusable or next-session-only.
  Declared settings are **not automatically verified**.
- **Per-core and PSI telemetry (M1.7 pretest):** per-core /proc/stat
  utilization deltas and system CPU/memory PSI avg10 rolling-pressure
  values, where readable; historical busiest-core/cluster statistics,
  observed frequency-ceiling timelines, and real sample charts with extrema,
  arithmetic mean and sample SD. Android access limitations are surfaced.
- **Diagnostics:** explicit on-device read-only CPU-policy, GPU, thermal,
  PSI, capability, permissions and database inspection, optionally exported
  as a local TXT report.
- **M1.8 DUKO analytics (in development):** one-minute monotonic interval
  summaries including medians/P95, coverage, energy evidence and PSI;
  per-policy CPU sampled clock residency/transitions with unknown-time
  accounting; and a unified retrospective event timeline for frequency caps,
  sustained load, battery temperature, OS thermal status and system PSI.
  Thresholds are heuristic and cannot establish FPS loss or cap causation.
  See [M1.8 specification](docs/M1_8_DUKO_BCD.md).
- **M1.6 archive continuity:** exact saved-session handoff, user-initiated
  refresh and incremental history expansion up to 100 recent completed runs.
  A/B computations reject rapid duplicate requests and preserve frozen
  report payloads across the Android document picker.

### Implemented beyond the earlier M1.8 roadmap

- DUKO M1.9: workload segments, selected-window suggestions and
  source-qualified findings.
- M2.0: interactive selected-range charts, timestamped live/post-run
  markers and original-row range CSV (SQLite schema v8).
- M2.1: 2–6-run evidence-gated comparisons and comparison-only
  offline PDF/PNG exports alongside A/B TXT/CSV.
- M2.2/K: bounded recorder read/SQLite latency histograms, missed
  deadlines and user-triggered process CPU/memory diagnostics.
- Phase 1: HUD status-subscriber failure isolation, partial-window
  rollback and latest saved-row consistency audit.

### Not yet confirmed or planned after the pretest

- Real Odin 2 Portal HUD/touch, sensors, battery/thermal and recorder
  CPU/memory overhead validation; longer recording and gameplay.
- Actual WindowManager attach/update/remove fault-injection on the device;
  guard tests alone are not sufficient.
- Stable APK distribution signing, upgrade safety across CI builds,
  final reviewed stacked-PR integration, accessibility/visual sign-off.
- Other handheld validation (Retroid, AYANEO, Anbernic); richer hardware
  detection where Android/vendor permissions allow.
- Future DUKO robust multi-run repeatability (MAD/outliers), behavior
  fingerprints, customizable reports and persistent HUD alert history.
- Automatic sampling continuation after process death is not claimed:
  original unfinished sessions are protected instead.
## Build and validation

Prerequisites: JDK 17, Android SDK 36 and Gradle 8.11.1.

```bash
gradle :engine:test :app:assembleDebug :app:assembleLeanDebug --no-daemon
# Emulator API 35, from the repository's Android CI workflow:
gradle :app:connectedDebugAndroidTest --no-daemon
```

The GitHub Actions workflow also builds release-equivalent R8 variants,
verifies permissions and APK checksums, and runs API 35 instrumentation
against the normal and separately optimized QA builds. **A green Android
build alone is not an accepted milestone.**

Get the v0.8.6 pretest artifacts **only from successful exact-head push
and PR workflow runs**. The expected artifact is
`HEIMDALL-v0.8.7-odin-test-lean-debug`. Verify the matching APK
SHA-256 manifest and commit before installing. CI debug signing can
change between runs; if an update reports a signature mismatch, STOP.
Do not uninstall Heimdall or delete recordings to make an APK install.
Controlling physical-test procedure:
[docs/PHASE1_V086_ODIN_PRETEST.md](docs/PHASE1_V086_ODIN_PRETEST.md).
Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
M1.6 quality gates:
[docs/M1_6_INTEGRATION_RELIABILITY.md](docs/M1_6_INTEGRATION_RELIABILITY.md).

Copyright © 2026 Aleksandar Dukovski. All rights reserved.
