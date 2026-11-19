# HEIMDALL

**We analyze how the handheld behaves, not how many frames the game renders.**

Android-native, offline-only handheld telemetry and analytics. One foreground
recorder service, one sampler, one local SQLite database; no root, Internet
permission, login, cloud account or FPS measurement claims. Fully independent
of the older CoreMetrics package.

## Current development branch: v0.5 M4 / optimized APK candidate

The prototype now includes:
- Dashboard START / STOP & SAVE with real persisted sample counts
- Read-only CPU load, detected CPU-policy clocks, optional GPU clock, battery,
  battery temperature and memory probes (unavailable sources are shown as —)
- Recording notification and a single shared state for dashboard, HUD and
  Quick Settings tile; a cold tile checks for unfinished recording evidence
- Optional permission-gated Canvas HUD with four original, shared visual
  presentations: Micro-Gauge Strip, Compact Glass Card, Horizontal Ribbon
  and Vertical Bezel Spine; independent touch-lock/unlock control
- Responsive, measured metric slot geometry, 48dp expandable actions,
  bounded real CPU-load history, three-level battery-temperature warning
  and explicit top/bottom/left/right dock positions
- Completed session history, on-demand median/P95/peak summaries with
  sample-coverage reporting and user-selected CSV export
- A user-initiated on-device capability report with optional local TXT export
  to identify readable CPU policies, GPU sources, PSI and thermal status
- Transactional SQLite **schema v2**: START, sample insert and durable STOP
  live in one database. v1 recordings migrate without being deleted
- Safe original-session handling after interruption: committed STOP is
  finalized on inspection; otherwise the original is held for manual STOP
- JVM tests, Android 15 emulator recording tests, fault-injection tests,
  device-diagnostic UI tests, four Canvas layout tests and CI install checks
- Optimized R8/shrinkResources QA APK plus normal debug build; GitHub
  reports exact APK bytes for both. The v0.4 debug APK was 20,579,418 bytes
- Nonblocking Android Service teardown; when a Service is destroyed, its
  coroutine sampler is cancelled immediately without blocking the main thread
- Independent best-effort battery and memory probes, so a restricted ROM
  cannot abort an otherwise working recording

**Testing is not a release.** Full game-overlay/controller behavior, sustained
performance, vendor ROM restrictions and real crash paths still require an
Odin 2 Portal acceptance run. Do not uninstall CoreMetrics or erase its data.

## Build and tests

Prerequisites: JDK 17, Android SDK 36, Gradle 8.11.1.

```bash
gradle :engine:test :app:assembleDebug :app:assembleLeanDebug --no-daemon
# On a disposable Android emulator:
gradle :app:connectedDebugAndroidTest --no-daemon
```

For an optimized, installable QA APK, use `HEIMDALL-v0.5.0-m4-lean-debug`
from a **successful** GitHub Actions run on the matching commit. CI also
ships an unshrunk debug build for test comparison. The lean QA build is
R8-minified and *debug-signed*, not a production release. It uses
`com.locutus5382.heimdall` and does not overwrite CoreMetrics. Upgrading
v0.2 to v0.3 should preserve Heimdall recordings via the schema migration.

Before accepting this candidate on Odin, use
[docs/V05_M4_SIZE_ACCEPTANCE.md](docs/V05_M4_SIZE_ACCEPTANCE.md).
Do not uninstall an existing Heimdall holding recordings without exporting
first: GitHub CI debug signing keys are not stable across workflow runs.
The diagnostics TXT contains only the device model, Android version,
permission/capability flags and database counts; it does not include a
hardware serial, file inventory or personal account data. Architecture and long-term
roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Still missing from the intended full product

- Device-specific power/energy and capability coverage, advanced thermal/PSI
- Final HUD real-device polish, controller-first interaction and native
  accessibility virtual nodes for drawn controls
- DUKO A/B reports, activity detection, integrity grading, TXT/PNG/PDF export
- Automated resumption of interrupted sampling (intentionally not claimed)
- Broad Odin/Retroid/AYANEO validation and Play Store release hardening

Copyright © 2026 Aleksandar Dukovski. All rights reserved.
