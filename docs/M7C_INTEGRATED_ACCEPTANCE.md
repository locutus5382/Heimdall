# Integrated Heimdall prototype acceptance — M7c

This is a **test plan, not a claim of hardware validation**. It applies to the
stacked development branch after M7b (PR #34), not `main`. The installable
lean-debug APK is debug-signed; preserve existing app data and do not uninstall
an installation with unexported recordings. CI success cannot substitute for
Odin 2 Portal testing.

## Gate 0 — exact commit and artifacts

- Record the candidate full SHA, draft PR base/head, Android version, Odin
  firmware, emulator/driver, power mode, fan mode, ClusterTune state and test
  date. Use a single exact-head **successful** GitHub Actions build; do not
  substitute older, cancelled, skipped or running workflows.
- Verify engine JVM tests, Android instrumentation, R8 optimized QA build and
  merged-permission audit. Confirm no INTERNET permission, root requirement,
  FPS collection, account or telemetry upload.
- Record APK artifact name and checksum before installing. Do not overwrite
  original SQLite evidence or claim a production release.

## Gate 1 — recorder and session evidence (P0)

1. Cold-start app; START once, wait for multiple samples, STOP & SAVE. Verify
   exactly one completed session, durable end timestamp and nonzero sample
   count after force-stopping and reopening the app.
2. Repeatedly tap START and STOP from both dashboard and Quick Settings,
   including two rapid QS taps. Confirm no duplicate recording, no replacement
   row for an unfinished session, and a coherent status in notification,
   dashboard and tile.
3. Force-stop or kill the process while recording; reopen. Confirm unfinished
   evidence is preserved, START refuses an unsafe new session, and recovery
   follows the existing durable-lease policy. Do **not** delete or rewrite
   raw sample rows as a repair shortcut.
4. Deny overlay permission: recording and STOP must remain functional.

## Gate 2 — HUD ownership and recovery (P1)

1. Enable HUD, record, lock it, and confirm the main Canvas is click-through
   while a separate tappable UNLOCK control remains reachable.
2. Rotate display and switch applications while locked; unlock, STOP and
   restart. At every point there must be at most one Canvas and one UNLOCK
   chip, never a second recorder or stranded click-through Canvas.
3. Revoke and restore overlay permission; verify recorder continues and
   overlay does not multiply or crash-loop.
4. Exercise failed `WindowManager.addView` and `updateViewLayout` for both
   Canvas and UNLOCK with a controlled fault-injection build/test harness.
   The existing predicate-only instrumentation is **not** proof of these
   paths. If UNLOCK fails, Canvas must become interactive or a valid UNLOCK
   must be restored. Verify STOP remains available from notification/QS.
5. Check 48dp touch targets and gamepad/gameplay touch pass-through.

## Gate 3 — DUKO evidence and exports

1. Record two short sessions with explicit driver, emulator, performance,
   fan and ClusterTune/underclock tags. Confirm unknown/unsupported device
   settings are manual declarations, never falsely auto-detected.
2. Compare full sessions and independently selected middle-80% and final-50%
   windows. Verify timestamps use monotonic recording time and empty/short
   windows have clear non-comparable outcomes.
3. Export DUKO Option B TXT and CSV via the Android document picker; check
   cancellation, permission/provider failure, content, selected windows,
   evidence grades, missing metrics and CSV escaping. No export should mutate
   original SQLite samples.
4. Verify unavailable sensors remain unavailable, not zero; no FPS or inferred
   game performance claims. Battery energy requires qualified discharge and
   calibration evidence. Thermal trends must identify their actual source.

## Gate 4 — physical Odin 2 Portal evidence (currently UNVALIDATED)

- Run 20–30 minutes of gameplay with HUD visible and repeat with HUD hidden.
  Observe stability, controls, sampling gaps, thermal behavior, battery data,
  Android memory use and battery drain. Do not infer causal performance
  improvement from a single run or from CPU/GPU utilization alone.
- Repeat across one driver or emulator setting change with recorded tags.
  Report firmware and environmental differences, sample coverage and
  confidence; compare like-for-like time ranges.
- Preserve logs and anonymized exports for review. Mark each gate
  PASS/FAIL/NOT TESTED and attach the candidate SHA. Do not publish or merge
  automatically.

## Remaining blockers

- Real Odin thermal, electrical, overlay, controller and gameplay checks are
  **not validated** by emulator CI.
- Issue #35 P2: actual WindowManager fault-path injection remains untested;
  the predicate-only test cannot close that gap.
- Stacked draft PRs must be reviewed and deliberately integrated; no
  successful CI run alone authorizes a merge or Play Store release.
