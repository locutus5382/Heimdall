# Heimdall 0.8.7 — Odin 2 Portal test candidate

**Not RC1. Not yet physically validated.** This candidate is stacked on CI-verified
Phase 1 0.8.6 (`77e5131bb04470238dfd47a21a020e887f163117`).
The individually preserved Odin-installable 0.8.0 baseline is unchanged.

## Stability hardening included

- Inherits 0.8.6 isolation: HUD rendering exceptions cannot terminate the
  recorder status subscriber; coroutine cancellations still propagate.
- When a Canvas or UNLOCK overlay fails to detach, the controller now
  retains its reference, conceals the stuck view, and retries release on
  later state updates. It will not attach a replacement while ownership
  remains unresolved. Similar safety applies to partial addView rollback.
- Canvas renderer triggers its emergency-disable callback once per
  consecutive 3-error streak, not on every subsequent draw pass.
- On-demand Diagnostics checks latest saved session's declared source count
  against the real SQLite row count without rewriting any evidence.

## Measured-overhead and CPU sampling optimizations

- CPU `cpuinfo_max_freq` silicon maxima are now cached for 60 seconds,
  rather than reading each policy's static file on every 2-second sample.
  Empty/unavailable snapshots are retried after the same 60-second window.
- Dynamic `scaling_max_freq`, current clocks, CPU per-core load, GPU,
  battery, thermal and PSI remain live, including frequency caps caused
  by different user-selected performance configurations.
- The HUD samples its current alert state once per status update rather
  than recomputing it twice; no extra device polling or database tables.
- Existing bounded recorder timing diagnostics remain available.
- This is a targeted reduction in I/O; device CPU, battery and frame
  effects must be measured on Odin, not estimated from the code alone.

## Required GitHub acceptance before installation

Use the FINAL EXACT SHA of `feature/phase1-v087-odin-test-candidate`.
Both push and pull_request GitHub Actions runs must finish SUCCESS.
Verify the engine tests, standard debug and R8-lean QA APK, release-like
checks, merged permissions, APK hash manifest, and both API35 Android
instrumentation variants. The new `PretestPerformanceSafetyAndroidTest`
exercises static-limit caching, error-coalescing and failed-window cleanup
ordering. Existing recorder, Bifrost and DUKO tests must continue to pass.

The expected artifact label is `HEIMDALL-v0.8.7-odin-test-lean-debug`.
Download its APK and SAME-RUN checksum manifest; the artifact ZIP digest is
not the APK digest. Keep the originally installed APK and exports.

**Critical installation safety:** GitHub-hosted debug signing keys may be
different between builds. If an in-place upgrade fails because of signing,
do NOT uninstall an app with recordings or clear Heimdall data. Stop and
report the error; plan a stable signing identity and data-safe migration.
Do not uninstall CoreMetrics.

## Odin test order (use docs/PHASE1_V086_ODIN_PRETEST.md)

1. Verify Dashboard START → nonzero persisted samples → STOP & SAVE, then
   reopen; Diagnostics latest saved row audit should be MATCH.
2. Rapid Quick Settings START/STOP and app/notification consistency.
3. Test Pill/Card/Ribbon/Spine with the game open, lock/unlock, rotation
   and overlay permission revoke/restore. Never test with touch-lock
   blocking critical device controls; use Quick Settings STOP if needed.
4. Record one 5-minute idle session and one 15–20-minute game session.
   Log read/SQLite/total p50/p95 upper bounds, misses, PSS and memory.
5. Review DUKO one-minute, CPU policies, events, selected-window A/B and
   multi-run summaries, source-aware warnings and PDF/PNG reports.
6. Export a sanitized diagnostics TXT and saved CSV(s), screenshots of
   any HUD/visual defects, and exact game/emulator/driver/mode/fan tags.

**If any P0/P1 fails, do not proceed to RC1.** Report exact commit,
reproduction, screenshots and source exports. Gameplay performance/FPS and
battery power attribution remain out of scope without direct evidence.
