# Heimdall v0.3 — Stability candidate / device acceptance

**This is a serious test candidate, not a production release.**
It does not reuse CoreMetrics classes and it installs under
`com.locutus5382.heimdall`. Preserve CoreMetrics and its data.

## Engineering changes over v0.2

- **Transactional SQLite v2** replaces split SQLite + SharedPreferences
  lease ownership. START, samples and durable STOP live in the same database.
  A v1 installation migrates in place; the v1 STOP flag is copied only if
  the legacy lease explicitly contains a committed STOP. No DROP TABLE.
- **Unfinished-session safety:** START refuses any older unfinished row.
  A committed STOP can be replayed for that same row. Without committed
  STOP, a user must inspect and STOP the original session. A missing
  sensor remains null; never guessed zero.
- **Quick Settings correctness:** before a cold tile can START, it checks
  the persisted database for unfinished rows. Such a tile says "Review
  interrupted session" and opens the dashboard; it cannot silently start
  a second recording. Active tile state means live recording, not error.
- **Service cleanup:** failed/rejected START and completed STOP no longer
  leave a recorder foreground notification indefinitely.
- **HUD:** one recorder-owned, permission-gated floating window with CPU,
  optional GPU clock, battery, optional battery temperature, red REC dot,
  STOP, drag grip and edge snap. An orange temperature warning requires
  >42°C for 15 seconds; amber is 38–42°C. Missing sensors show a dash.
  The HUD is not a second telemetry engine.
- **Useful session review:** completed session cards and a read-only
  on-demand CPU/GPU median/P95/peak summary with coverage counts,
  battery start/end and peak battery temperature; no FPS claims.
  CSV exports use the actual persisted samples.

## Acceptance gates before installation recommendation

1. GitHub CI: `:engine:test` and `:app:assembleDebug` pass.
2. GitHub emulator: `:app:connectedDebugAndroidTest` passes,
   verifying two independent recordings and same-ID STOP, SQLite v2
   durable STOP marker, session history and CSV consistency.
3. Emulator simulated interruptions: stranded row blocks START and
   can be finalized by user STOP; already durable STOP finalizes
   the original row without creating another recording.
4. No Internet permission, root requirement, duplicate recorder
   or replacement of CoreMetrics package.
5. All jobs must be GREEN at the SAME HEAD COMMIT.

## Odin 2 Portal device acceptance checklist

Install the v0.3 APK from a successful build (not an earlier ZIP).
Keep CoreMetrics installed; Heimdall uses its own app and SQLite database.

- [ ] Open/close dashboard several times; no crash and no duplicate sessions.
- [ ] Tap START and play in Eden for 10–15 minutes. Confirm count rises
      every ~2 seconds and notification stays visible in background.
- [ ] Return to dashboard; session ID and persisted count remain consistent.
- [ ] Tap STOP, check the saved row; repeat a second session. IDs must differ.
- [ ] Open ANALYZE: check CPU median/P95/peak and data coverage. No made-up
      zero for unavailable metrics.
- [ ] EXPORT CSV: saved file has one header row and one row per saved sample.
      No network permission or online account is needed.
- [ ] Add Heimdall QS tile, start and stop using it, and compare tile state
      with the dashboard. Only live recording may show an active tile.
- [ ] Enable "Display over other apps", enable HUD, launch a game. See one
      floating pill, CPU/BAT data and STOP. Drag by the grip, check edge snap
      and no duplicate overlays after app recreation.
- [ ] Press HUD STOP. It must save the original session and remove the HUD.
- [ ] Interrupt a recording by killing its process (not Force stop or
      clearing storage), reopen Heimdall and confirm no automatic replacement
      session; press RETRY STOP to finalize the original.
- [ ] Install v0.3 *over* v0.2, without uninstalling, and verify previous
      saved samples and exports remain readable (SQLite 1→2 migration).
- [ ] Collect logs/screenshots for any crash, missing metric, incorrect
      tile state, stuck notification or duplicate window.

## Known boundaries / non-goals

- Not a Play Store release, not a fully validated product across devices.
- Full background resumption after process kill is intentionally prohibited:
  an interrupted original is preserved and must be finalized safely.
- HUD is one recording-time layout, not the future three fully polished
  variants or standby REC pill. Controller D-pad and touch-lock come later.
- Device hardware may restrict `/proc/stat`, CPU policy or KGSL access.
  This is displayed as unavailable, not as a measured zero.
- Percentile summaries use *available samples only* and nearest-rank P95;
  missing observations lower coverage. The app does not measure FPS.
- A release gate remains physical Odin validation plus later Retroid/AYANEO
  portability work. Do not delete CoreMetrics yet.

Copyright © 2026 Aleksandar Dukovski. All rights reserved.
