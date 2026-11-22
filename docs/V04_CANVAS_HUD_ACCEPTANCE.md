# Heimdall HUD 2.0 — v0.4 Canvas candidate

## Scope

This branch changes **presentation only**. The recorder, SQLite v2,
background service, sample clock, Quick Settings and CSV format are unchanged.

New original, independent implementation:
- `HeimdallCanvasView` performs all telemetry drawing using Android Canvas,
  with no per-metric TextView hierarchy, no sensor reads and no render loop.
- Three coherent layouts: Compact Pill, Compact Glass Card, Horizontal Ribbon.
- The CARD uses a bounded 36-sample CPU utilization sparkline. This graph
  is **not frametime or FPS**, and missing CPU data creates a gap.
- `HudProfileStore` persists layout, opacity, scale, selected metrics and
  touch-lock state locally. HUD Studio edits the actual installed HUD.
- The foreground Service owns precisely one Canvas HUD WindowManager window.
  No separate sampler and no secondary HUD instance.
- MODE cycles through the three layouts, LOCK sets the main window
  FLAG_NOT_TOUCHABLE (for game touch pass-through), and STOP dispatches to
  the original foreground recording service.
- Lock creates a separately owned 64×48dp **UNLOCK** chip, positioned outside
  the main overlay rectangle where possible. Both windows are torn down when
  recording ends, permission is lost, or the service dies.
- Locked main window alpha is capped at 0.72 to comply with Android 12+ overlay
  touch-pass-through obscuring rules; cross-OEM behavior requires real testing.
- Samples update the HUD at the recorder's cadence. Canvas repaints only on a
  snapshot/profile change; no independent 60fps animation or polling.
- Battery temperature uses the previous 38/42°C warning with a 15s hot
  confirmation; high CPU/GPU usage does not mean throttling.

## Automated gates

- [ ] Same-commit engine JVM tests including the HUD model and ring buffer
- [ ] Same-commit Android APK build
- [ ] Same-commit Android emulator UI tests including Canvas rendering across
      all three layouts, actual touch hit targets, and preference round trips
- [ ] Existing recording persistence, interrupted-session recovery and CSV
      tests stay green
- [ ] Device and software package remain isolated from CoreMetrics

## Required Odin Portal verification

Before approving a merge or release:
1. Install this APK as a test build. **GitHub debug signing certificates vary**
   between builds, so first export important Heimdall data and do not
   uninstall an existing APK without preserving it.
2. Grant overlay permission and enable HUD. Start from dashboard; verify
   exactly one Canvas pill appears on top of Eden.
3. Tap MODE repeatedly: Pill → Card → Ribbon → Pill. Look for clipping,
   oversized fonts and unusable touch targets in landscape and portrait.
4. Open HUD Studio during recording. Change opacity, scale and metrics.
   Return to game: the same HUD changes without a second session/window.
5. Try dragging the HUD, edge snapping and position persistence after
   dashboard reopening and device rotation.
6. Tap LOCK. Game inputs should pass through outside the small unlock chip.
   The 48dp-high unlock chip must remain independently tappable.
7. Tap UNLOCK, then STOP. HUD and unlock chip must disappear, original
   recording session must finalize, and the original sample count must match
   the exported CSV.
8. Test QS START/STOP, notification STOP, interrupted-session recovery and
   app Activity recreation. No duplicate HUD or false REC state.
9. Run a 30-minute game, compare frame smoothness/CPU use with HUD off,
   and verify no background telemetry sampler besides the recorder.

## Known limitations

- This is a HUD alpha, **not** the final glass visual polish or production UI.
- True cross-application backdrop blur is not provided by Android Canvas.
- Canvas button labels are drawn and hit-tested; separate accessibility
  virtual nodes for those painted controls still need implementation. STOP
  remains available through the Android notification and dashboard.
- Portrait layouts can scale below nominal 48dp control size. Validate
  hardware landscape before deciding responsive breakpoints.
- GPU load, CPU/GPU temperatures, power draw, frametime/FPS and DUKO A/B
  reporting are not added or invented in this branch.
- Android 12+ overlay click-through behavior must be checked on each ROM.

Copyright (c) 2026 Aleksandar Dukovski. All rights reserved.
