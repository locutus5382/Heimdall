# HEIMDALL

**We analyze how the handheld behaves, not how many frames the game renders.**

A new Android-native, offline-only handheld telemetry and analytics app.
One recording engine, one foreground service, one local database and a coherent
cobalt/marigold UI. No root or Internet permission.

## Current status: F0 foundation (not a public release)

The first branch implements:
- A pure Kotlin recording coordinator with STOP-before-finalize semantics
- An independent Android app ID: `com.locutus5382.heimdall`
- A native foreground recorder service and SQLite v1 sessions/samples
- Read-only battery, memory, CPU-load, CPU-clock and optional GPU-clock probes
- A first Compose dashboard with live persisted sample counts
- JVM lifecycle tests and a GitHub Actions build
- An F0 acceptance branch adds a Compose dashboard-to-SQLite instrumentation
  test on a disposable Android emulator; test execution is not yet verified.

Not implemented yet: automatic crash recovery, polished HUD variants, Quick
Settings, device-specific validation, A/B analytics, DUKO reports, exports and
Play Store readiness. None of the old CoreMetrics code is imported.

## F0 acceptance

Before F0 can be called complete, both CI jobs must pass and the recorder must
pass a physical Odin 2 Portal test. See [F0_ACCEPTANCE.md](docs/F0_ACCEPTANCE.md).
Neither code presence nor a generated APK alone proves the recording workflow.

## Build

Prerequisites: JDK 17, Android SDK 36 and Gradle 8.11.1.

```bash
gradle :engine:test :app:assembleDebug
# With a running compatible Android emulator/device:
gradle :app:connectedDebugAndroidTest
```

Use the GitHub Actions debug APK from a **successful** CI run. Its app ID is
separate from `com.locutus5382.coremetrics`. Do not install it as a replacement
for CoreMetrics or assume that it can recover old CoreMetrics recordings.

Architecture, safety decisions and staged feature plan: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Copyright © 2026 Aleksandar Dukovski. All rights reserved.
