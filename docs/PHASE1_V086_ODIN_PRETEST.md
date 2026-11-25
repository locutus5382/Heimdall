# Heimdall Phase 1 — v0.8.6 Odin 2 Portal pre-RC1 acceptance

**This is a hardware test plan. All Odin cases are NOT TESTED until the
device owner submits results.** A successful emulator pipeline does not
constitute real-device acceptance, GPU/thermal calibration or an RC1 release.

## Exact test artifact and preservation

- Working branch: feature/phase1-odin-stability-acceptance, stacked on the
  exact CI-green v0.8.5 M2.2/K SHA c3ff86a31fc68ecd7fe84a5f76c98ec7d4292140.
- App version: 0.8.6-odin-pretest-stability-alpha, versionCode 15.
- Use only the **exact-head green push and PR runs**, then download artifact
  HEIMDALL-v0.8.6-odin-pretest-lean-debug, together with the matching
  APK checksum manifest from the SAME run.
- In addition to standard build, engine tests, R8 optimized release-like
  builds, permission audit and both API35 app test variants, check new
  HUD-delivery/rollback and SQLite read-only audit test results.
- Save original v0.8.0 APK and any existing session exports. Debug APKs from
  independent Actions runs can have incompatible signing keys. On an upgrade
  signature error **STOP** — do not uninstall Heimdall or clear its database
  merely to install this build. An empty isolated test device/profile may
  be used after checking it does not affect existing data. CoreMetrics must
  not be uninstalled.
- Confirm package com.locutus5382.heimdall; do not request INTERNET, root,
  screen capture or automatic control of the Odin's hardware.

## Test header to complete

- Exact GitHub source SHA / APK checksum:
- Odin 2 Portal Android version, build/firmware:
- Device performance mode / fan / ClusterTune state:
- Emulator, graphics driver, game and test scene:
- Charge state and approximate battery temperature at start:
- Orientation / font scaling:
- Existing Heimdall sessions preserved: PASS / FAIL / NOT TESTED
- Notes and sanitized evidence links:

## Gate 1: recorder and UI consistency — P0

| ID | Action | PASS means |
|---|---|---|
| P1-01 | Launch and start, record at least three samples, STOP & SAVE | Exactly one completed row; sample count nonzero; no second recorder |
| P1-02 | Start/stop from Quick Settings; then inspect the Dashboard | Tile, notification, app and persisted session ID agree |
| P1-03 | Rapid QS START / STOP / START taps | No phantom duplicate recording, stuck command or replaced unfinished row |
| P1-04 | Background app and open an emulator while recording | Same recording continues; Dashboard returns to same session |
| P1-05 | Rotate/change screen while recording | START and STOP reachable; no duplicate HUD or state loss |
| P1-06 | Reopen after normal STOP | Completed ID and raw sample count remain stable |
| P1-07 | Simulate app or Service interruption only after exporting valued runs | Original evidence survives; no fake recovered samples; no unsafe auto-START |

## Gate 2: HUD and input — P0/P1

| ID | Action | PASS means |
|---|---|---|
| P1-08 | Use each of Pill/Card/Ribbon/Spine in game | Readable and only one Canvas HUD window |
| P1-09 | Change HUD metrics/position/dock/scale | Layout stays on screen and never duplicates |
| P1-10 | Lock HUD, tap through game, unlock using separate 48 dp chip | Touch reaches game; unlock always accessible |
| P1-11 | Revoke HUD permission and/or disable HUD while recording | Recording remains active; overlay safely detaches |
| P1-12 | Background/foreground and rotate while HUD locked | No orphan unlock chip; STOP remains accessible via app/QS |
| P1-13 | Open Setup and edit driver/tag text with controller attached | L1/R1/X/Y/B do not steal text input; touch navigation works |
| P1-14 | Navigate Dashboard/Sessions/Compare/blades | Back/Close consistently restores prior screen and scrolling works |

**Not closed by CI alone:** real WindowManager attach/update/remove errors
under vendor overlays; calibration of touch pass-through on the Odin, especially
rotated and in-game. Emulator guard tests prove only failure handling logic.

## Gate 3: DUKO accuracy and export — P1

| ID | Action | PASS means |
|---|---|---|
| P1-15 | Inspect CPU core usage, clocks, battery/CPU/GPU temps and PSI | Unsupported sensors show unavailable, never zero |
| P1-16 | 5–10 minute session; view MINUTES, CPU POLICIES, EVENTS, INSIGHTS | Monotonic minute boundaries, source gaps and qualified findings are honest |
| P1-17 | Tag driver, fan, performance and ClusterTune before another session | Only user-declared context; no automatic false detection |
| P1-18 | Add one live and one post-run marker | Correct timestamps and original sample rows remain immutable |
| P1-19 | Select partial monotonic range; export original-range CSV | Source IDs/timestamps and expected in-range row count match |
| P1-20 | A/B comparison of two comparable runs; compare selected windows | No false game performance winner; context warnings present |
| P1-21 | Multi-run comparison of 3+ sessions; promote A baseline | Correct selection and evidence-qualified patterns |
| P1-22 | Export A/B and multi-run PDFs and PNGs | All rows/pages legible; no clipped warnings; cancellation writes nothing |

## Gate 4: performance overhead and device stability — P1

| ID | Action | PASS means |
|---|---|---|
| P1-23 | Open Diagnostics after an idle recording | Read/append P50/P95 bins, maxima and missed deadline counts visible |
| P1-24 | Run same scene: recorder OFF/HUD OFF → ON/HUD OFF → ON/HUD ON → OFF | Record CPU/PSS/thermal/latency evidence, no reproducible unacceptable overhead |
| P1-25 | Complete 20–30 minutes gameplay recording (battery/fan safe) | No increasing memory without bound, dropped recorder, duplicate HUD or stuck UI |
| P1-26 | Open Diagnostics after STOP | Latest saved sample-count integrity is MATCH; if MISMATCH preserve and report |
| P1-27 | Try diagnostics while recording and after restart | Snapshot is clearly in-memory and may become unavailable after process death |

**Interpretation:** recorder process CPU time includes other app threads;
battery terminal current is not game-specific watts; emulator performance
numbers cannot be used to certify Odin hardware; no FPS claims. Mark any
unavailable evidence explicitly.

## Severity/decision

- **P0 STOP:** raw evidence loss, a replaced unfinished session, duplicate
  active recorder, unsafe HUD lock trapping touches, inaccessible STOP.
- **P1 STOP RC1:** recurrent HUD crashes/double overlays, state divergence,
  broken navigation/controller focus, corrupted export, inaccurate source
  claims, repeatable unacceptable overhead, failed Android regression gate.
- **P2 follow-up:** cosmetic spacing, formatting, optional metrics missing
  because OEM blocks access (provided the UI reports unavailable honestly).
- Record each case as PASS / FAIL / NOT TESTED with version, exact repro steps,
  screenshot and diagnostics/session export where safe. Never report
  "all tests passed" while physical device results remain NOT TESTED.

## Post-test integration decision

Only after P0/P1 failure triage and the physical acceptance matrix:
1. Freeze source changes and reconcile stacked draft PRs against their bases.
2. Build one combined, exact-commit, reviewed release-candidate branch.
3. Validate data-preserving upgrade path with stable signing and migrations.
4. Run normal and lean/R8 Android tests on the combined SHA.
5. Re-test the Odin with the **integrated** candidate before calling it RC1.
6. Do not auto-merge to main, publish or delete session data.
