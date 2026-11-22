# HEIMDALL v0.3.1 — Odin 2 Portal validation

Candidate status: Android CI must pass before hardware installation.
CoreMetrics stays installed; Heimdall has its own app ID and database.

## What changed since v0.3

- Foreground Service teardown now cancels its sampler without blocking the
  Android main thread; notification taps reopen the active dashboard.
- Battery/memory sensor failures are isolated: an inaccessible ROM service
  should give unavailable readings, not abort the whole recording.
- RUN DIAGNOSTICS inspects Android model/version, CPU policies, GPU clock
  source, /proc/stat, CPU/memory PSI accessibility, thermal status, overlay
  permission, notification permission, and SQLite state.
- EXPORT TXT writes the diagnostics snapshot to a user-selected local file.
  No network or unique device identifiers are collected.

## Required automated gates

- Same-commit Android debug build, engine tests and API 35 emulator tests pass.
- Device diagnostics UI works even when /proc or /sys are blocked.
- Recorded samples, SQLite recovery, history and CSV fidelity remain valid.

## Physical Odin acceptance (manual)

1. Install over earlier Heimdall without uninstalling. Verify old recorded
   sessions and CSV exports are still available.
2. Tap RUN DIAGNOSTICS and EXPORT TXT. Record which CPU, GPU, PSI, and thermal
   sources were genuinely available.
3. Start recording and play in Eden for 10–15 minutes. Return via the ongoing
   notification. The SAME session ID and increasing disk count must remain.
4. Stop and save, analyze the completed session, export CSV. Unsupported data
   fields must remain empty, not appear as invented zeros.
5. Add Heimdall Record from the Android Quick Settings tile editor. Test
   START/STOP and subsequent START. Compare tile, notification, dashboard.
6. Grant overlay permission and enable HUD. Check exactly one pill in game,
   drag/snap by the handle, press HUD STOP, verify finalization and removal.
7. Interrupt a session without clearing data. Reopening should show the
   original unfinished ID; START remains blocked and RETRY STOP finalizes it.
   Force stop is not equivalent to a controlled process-death injection.
8. Run a longer 30–45 minute game session; confirm stable sample frequency,
   memory behavior, HUD, notification and preserved exported sample count.

For anomalies, provide the diagnostics TXT, the session CSV and screenshots.
Serial numbers, user accounts, other app names and private data are not needed.

## Remaining boundaries

Not Play Store ready. Advanced DUKO A/B reports, multi-device validation,
power consumption modeling, touch lock, controller UI and three final HUD
variants remain future phases. A successful emulator test does not certify
Odin ROM behavior.

Copyright (c) 2026 Aleksandar Dukovski. All rights reserved.
