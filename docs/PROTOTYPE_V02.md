# Heimdall v0.2 — testable prototype

Status: **source implementation on a separate branch; not production release**.
Only mark this prototype ACCEPTED once GitHub's Android build, pure-engine
tests, emulator instrumentation tests and a physical Odin 2 Portal check pass.

## What works in code

- **Dashboard:** START and STOP & SAVE use one native foreground recorder.
  Persistent sample count and optional read-only metrics appear as recorded.
- **Quick Settings:** Android tile named `Heimdall Record`, manually added in
  Android's QS edit view. User taps to start/stop the same Service; no tile-owned
  sampler or duplicate HUD. If Android denies starting from a background tile,
  Heimdall opens its dashboard instead of claiming START succeeded.
- **Optional HUD:** first single-instance Cobalt/Marigold Pill. During a
  recording, shows CPU load, battery percent, red recording dot and STOP.
  The HUD is off by default; tap the dashboard HUD control, grant "Display
  over other apps", return and tap again to enable. Android can refuse an
  overlay even with permission, in which case recording should continue.
- **Saved sessions:** completed SQLite rows appear in the dashboard.
- **CSV export:** choose CSV for a completed session and save through Android
  Storage Access Framework; no storage permission, account or Internet.
- **Partial interruption recovery (F1a):** durable STOP completes the original
  session on inspection. Unstopped sessions require user STOP. No fake
  resumption and no automatic replacement session.

## What to test on the Odin 2 Portal

1. Keep CoreMetrics installed. This is a separate package with a new database.
2. Install the debug APK from a **successful** Heimdall prototype workflow.
3. Start a recording from the dashboard and verify sample count increases.
4. Stop from the dashboard, confirm a completed session appears, export CSV.
5. Use Android's Quick Settings editor to add `Heimdall Record`. Test
   tile START and STOP; verify a single session and no inconsistent states.
6. Enable HUD through the dashboard and explicit OS overlay permission.
   Open Eden or a game; confirm one Pill is present and its STOP button
   finalizes the original session. No duplicate overlays.
7. Move the app to background and back. Verify same session ID, stable
   notification and no second recorder.
8. Simulate an app process interruption (not "Force stop", which is a
   different OS state). Reopen dashboard and observe the recovery status.
   Tap STOP for the original unfinished session.
9. Report Android/ROM version, crashes, unsupported metrics and CSV sample
   counts. More capability-aware telemetry comes in F2.

## Deliberate boundaries

- No claim of FPS measurements, underclocking, device control or root.
- No standby HUD REC yet: Pill appears **only during recording** and only
  when enabled. This avoids a persistent service while idle.
- Current QS status is a same-process projection; after OS process death,
  START is ultimately validated against SQLite by the Service. It is
  not yet a persistent live-binder status protocol.
- No polished HUD variants, touch lock, drag/snap, controller controls,
  DUKO reports, advanced analytics or full portability yet.
- Unavailable CPU/GPU readings must show a dash, never guessed values.
- For wide compatibility, overlay start and QS tile behavior need real-device
  testing on Android 13/15 and vendor ROMs. A green emulator test alone
  is not a real-device verification.

## Prototype acceptance gates

- :engine:test and :app:assembleDebug pass.
- :app:connectedDebugAndroidTest succeeds on disposable Android emulator.
- Real dashboard START/STOP creates one completed session with persisted rows.
- The same completed rows appear in history and are exported faithfully to CSV.
- Odin shows stable recording with no crashes, duplicate windows or active
  session mismatches from QS, HUD and dashboard.
- No CoreMetrics classes, Internet permission, FPS claims or extra recorder.

Copyright (c) 2026 Aleksandar Dukovski. All rights reserved.
