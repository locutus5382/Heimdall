# HEIMDALL

**We analyze how the handheld behaves, not how many frames the game renders.**

Heimdall is an Android-native, offline, read-only telemetry and DUKO analysis
app for handhelds. It has one foreground recorder Service, one sampler, one
private SQLite database and one optional Canvas HUD. It does **not** measure
FPS, control clocks/fans, require root, ask for an account, or send recordings
to a server. Independent of the older CoreMetrics package.

## Current development state (23 November 2026)

The Bifrost touchscreen-first UI integration is being validated in draft
[PR #54](https://github.com/locutus5382/Heimdall/pull/54), branch
`feature/m1-bifrost-ui-rebuild`. M1.6's exact-head Android CI is a **required
gate**; M1.7 physical Odin 2 Portal validation has **not** been completed.
**This is not RC1, a stable signed release, or a Play Store build.**

The Gradle app version currently remains
`0.8.0-percore-psi-alpha` (versionCode 9). Development milestone numbers
M1.6 / M1.7 refer to **integration work**, not that Gradle version.

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
- **M1.6 archive continuity:** exact saved-session handoff, user-initiated
  refresh and incremental history expansion up to 100 recent completed runs.
  A/B computations reject rapid duplicate requests and preserve frozen
  report payloads across the Android document picker.

### Not yet confirmed or not implemented

- Physical Odin 2 Portal lifecycle/HUD and long-session performance
  validation, plus vendor-specific telemetry reliability.
- Broad Retroid, AYANEO, Anbernic and other Android handheld verification.
- Optional richer PNG/PDF reports, deeper energy calibration and some
  advanced activity/event analytics. Do not claim that battery terminal
  readings equal game-specific power consumption.
- Stable distribution signing, reproducible upgrade path across CI runs,
  RC1/Play Store packaging, final accessibility and visual acceptance.
- Automatic continuation of sampling after arbitrary process death is
  **not** claimed; unfinished original evidence is protected instead.

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

The CI artifacts `HEIMDALL-v0.8.0-percore-psi-debug` and
`HEIMDALL-v0.8.0-percore-psi-lean-debug` contain test APKs and a SHA-256 manifest.
Install only from a **successful exact-commit run**. CI debug signing
certificates can change between runs, so `adb install -r` may reject an
upgrade. **Never uninstall Heimdall to bypass a signing mismatch if its
database contains recordings.** Export before migrating. Do not uninstall
CoreMetrics.

Next physical-test procedure:
[docs/M1_7_ODIN_PORTAL_ACCEPTANCE.md](docs/M1_7_ODIN_PORTAL_ACCEPTANCE.md).
Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
M1.6 quality gates:
[docs/M1_6_INTEGRATION_RELIABILITY.md](docs/M1_6_INTEGRATION_RELIABILITY.md).

Copyright © 2026 Aleksandar Dukovski. All rights reserved.
