# Heimdall Bifrost M1.3 / M1.4g — Integration and acceptance

## Scope and constraints

This checkpoint **does not** replace NativeRecorderEngine, SQLite schema v6,
the native Canvas HUD, Quick Settings tile, or the evidence-qualified DUKO
accumulators. The app stays offline, read-only toward hardware controls,
without FPS claims, root or network access.

### M1.3 implementation boundaries

- `SessionScreenViewModel` owns completed-run history, expanded session ID,
  asynchronous DUKO analysis, error state, and the selected pending CSV export.
  It rejects duplicate picker requests; a rotated Activity can resume the same
  pending export through its surviving ViewModel. If the pending ID has been
  lost (e.g. process death), it fails closed rather than writing another run.
- `CompareScreenViewModel` owns A/B selection, two independent analysis windows,
  cancellation and generation of bounded read-only comparison requests.
  TXT/CSV payloads are **snapshotted before SAF opens**; a later UI selection
  cannot silently change the exported run/window. A lost payload fails closed.
- Both screens are constructed once per MainActivity ViewModelStore. UI
  composables observe lifecycle-aware immutable `StateFlow` projections.
  Repository interfaces admit fake data sources in Android tests.
- Next-run test conditions explicitly distinguish reusable tags from
  `NEXT SESSION ONLY`. The latter are snapshotted into the same session START
  transaction as device evidence and are cleared only after commit, provided
  the saved draft revision is unchanged. A process crash in the narrow gap
  between database commit and preference clearing can leave a one-run preset
  visible; users must still confirm the captured tags, and exactly-once
  cross-store consumption is **not claimed**.
- UI text remains explicit that tags are USER_DECLARED, device sensors may be
  unknown, DUKO is not FPS, and a comparison is non-causal.

### M1.4g integration gates

1. **CI exact-head:** `:engine:test`, Android debug/optimized/R8 builds,
   manifest permission/integrity checks and API 35 instrumentation all green
   for the *same* source SHA. A successful build alone is insufficient.
2. **Recorder round-trip:** UI Start -> foreground Service -> persisted samples
   -> STOP & SAVE -> completed SQLite row -> CSV export, with Activity
   recreation and a second session. The diagnostic test reports the exact
   await-stage and recorder state on any timeout.
3. **History/analysis:** select a saved run; before/after tab switch and
   rotation the expanded run's analysis and associated evidence must belong
   to that run, never another. Interrupted/insufficient sessions retain honest
   warnings. No stale run's analysis may overwrite a newer selection.
4. **Compare:** select two distinct completed runs; preserve A/B and custom
   windows on tab changes and rotation. Invalid/out-of-range windows are
   rejected, not silently clamped. Export a frozen TXT/CSV A/B report even
   if selections change while Android's document picker is open.
5. **Profiles:** reusable and next-run-only tags display accurately on
   Dashboard. The SQLite START snapshot is immutable. Test the draft-revision
   guard so a concurrently edited profile is not erased by old START.
6. **Controller:** L1/R1 navigate while shell owns focus; B/Android Back
   closes a blade before navigating; focused editable fields are not
   intercepted. Scrim is not gamepad-focusable. Accessibility labels, tab
   roles, heading semantics, and minimum touch targets are checked.
7. **HUD/Quick Settings:** same actual recorder state, no optimistic success,
   duplicate HUD windows, false overlay visibility, or second sampling loop.
   Denied/revoked overlay permission must not stop recording.

## Physical Odin 2 Portal acceptance — NOT TESTED

Using a disposable Heimdall build with data backed up:

- Record one idle/warm run of at least 5 minutes, then two matched gameplay
  runs with manually recorded emulator, driver, fan, performance mode and
  ClusterTune settings. Validate sample count and integrity/coverage.
- Start/stop via Dashboard, Quick Settings and HUD; verify all three states
  agree and that STOP closes only the original session.
- Toggle overlay permission, HUD Studio layout/dock/lock, launch a game,
  switch focus, reopen after interruption, and assert only one HUD instance.
- Exercise touch, physical L1/R1/B/X/Y, focused text editors and device
  rotation in both portrait and landscape.
- Compare runs with independent custom monotonic windows and save TXT/CSV
  through Android SAF. Compare exported contents to the selected session IDs.
- Restart the app and service between tests. Ensure prior DB rows persist
  and interrupted sessions are never silently replaced.
- Measure real UI responsiveness, battery consumption and app/HUD stability;
  CI or emulator runs do not establish physical hardware behavior.

## Release policy

Do **not** merge the M1 branch, label RC1, publish, or claim Odin acceptance
until all exact-head automated gates pass and the physical test results have
been recorded. Track CI failures with the specific failing phase rather than
masking them with broader timeouts. Main and CoreMetrics remain untouched.
