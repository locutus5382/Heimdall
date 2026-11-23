# Heimdall M1.7 — Odin 2 Portal physical acceptance

**Status: PREPARED, NOT RUN.** This is the procedure for moving from
M1.6's emulator-tested Bifrost branch to a **device-tested** alpha. Nothing
in emulator CI proves in-game overlay correctness, sampling availability on
an Odin, fan integration, long-run stability or controller compatibility.

## Preparation — protect recordings before installation

1. Use a **successful** GitHub Actions run for the exact commit on
   `feature/m1-bifrost-ui-rebuild`; confirm the Android build/engine **and**
   standard + optimized API 35 instrumentation jobs are green. Get
   `HEIMDALL-v0.7.0-duko-debug` (unshrunk) or
   `HEIMDALL-v0.7.0-duko-lean-debug` (optimized test APK) and the
   matching `heimdall-apk-sha256.txt` from **that same run**.
   Names reflect the current Gradle `0.7.0-duko-foundation-alpha` version,
   not M1.7 release approval.
2. **Preserve data.** Do not uninstall Heimdall or CoreMetrics. GitHub-hosted
   CI runners create ephemeral debug signing keys and a replacement APK from
   another run may fail to upgrade an existing installation.
   If Android rejects an update due to a signature mismatch, **stop**.
   Don't work around the mismatch with uninstall or `adb install -r -d`.
   Export valued Heimdall recordings first and plan a stable signing key
   before production upgrades.
3. Verify APK checksum with the companion manifest using
   `scripts/verify_rc1_apk.py` or independently compare SHA-256. Install
   only if package ID is `com.locutus5382.heimdall` and checksum matches.
   Optional USB workflow: `adb install -r app-debug.apk`. No root is needed.
4. On Odin 2 Portal, record OS version, performance preset, fan preset,
   battery level, whether charging, graphics driver, emulator/version and
   ClusterTune state **as declared settings**. Never assume the app can
   read or verify these automatically. Note screen orientation and display
   scaling/font size.
5. Open **Diagnostics** and run inspection before first test. Export TXT
   using Android's document picker. Record missing GPU/CPU/thermal/PSI
   readings as *unavailable*, not zero. Check SQLite journal/schema and
   completed/recording session counters.

## A. Touch-first UI and settings — 10 checkpoints

| ID | User action | Required result |
|---|---|---|
| UI-01 | Launch without controller; switch Dashboard → Sessions → Compare → Dashboard by touch | No crash, all tabs accessible, no focus prerequisite |
| UI-02 | Open/close Setup, HUD Studio, Run Picker and Diagnostics | Modal closes on touch/Back, no stale layer or hidden controls |
| UI-03 | Rotate portrait/landscape; change Android font scaling | No clipped primary Start/Stop, tab strip or blade close action |
| UI-04 | In Test Setup enter game/emulator/driver; choose next-session-only | Unsaved-change feedback; explicit SAVE; Dashboard shows declared profile |
| UI-05 | Reopen saved Setup and switch its pages/rotate | Tags persist until saved; no silent overwrite by another session |
| UI-06 | Pick four HUD themes; dock, metric chips, opacity, scale; inspect real renderer preview | Controls respond to touch; no invented sensor readings |
| UI-07 | Disable overlay permission; enable HUD preference | Honest permission-required indication; no claim HUD is on-screen |
| UI-08 | Restore HUD defaults using confirmation | No accidental single-tap loss of saved HUD settings |
| UI-09 | Open Diagnostics, run probe, export TXT; rotate | Results stay tied to the same inspection and export |
| UI-10 | Optional L1/R1 and Back while navigating, then edit a text field | Controller never steals keyboard focus or blocks touch |

## B. Recording lifecycle and HUD — 10 checkpoints

| ID | User action | Required result |
|---|---|---|
| REC-01 | Start on Dashboard | Start becomes real **Recording** only after Service/SQLite confirms it |
| REC-02 | Record idle for at least 5 minutes, screen awake | Samples accumulate; no unexplained dropouts or duplicate sessions |
| REC-03 | Background app; return after 1–2 minutes | Same session ID and accumulated samples, no second sampler |
| REC-04 | Open game while recording, enable permitted HUD | One overlay window; readable metrics, no fabricated FPS |
| REC-05 | Drag/dock, expand/collapse and lock/unlock HUD | Touch is responsive; lock does not trap unlock control |
| REC-06 | Stop via Dashboard | STOP & SAVE eventually yields one completed, original session |
| REC-07 | Tap REVIEW SAVED SESSION #ID | Opens exact new ID, with matching recorded sample count |
| REC-08 | Start via Quick Settings, stop via tile or notification; reopen app | One recorder state; Sessions REFRESH discovers completed run |
| REC-09 | Repeat start/stop via HUD if offered by active layout | Dashboard/tile/HUD agree; no duplicate overlay or double STOP |
| REC-10 | Revoke overlay grant / disable HUD while recording | Recorder continues independently; UI reports missing overlay access |

## C. Evidence and analysis — 7 checkpoints

1. Create **Run A** and **Run B** for the same game/scenario, with start
   conditions explicitly tagged: emulator/version, graphics driver, fan,
   performance mode, and ClusterTune state. Change **one** controlled
   variable at most. Capture a cold/warm distinction in notes.
2. Each run should contain a long enough valid sample interval (recommend
   >=5 minutes) and persisted monotonic timestamps. Do not invent frame
   counts, battery Wh, GPU load or missing sensors.
3. Open each session's Summary, DUKO Analysis, Test Conditions and Export.
   Check sample count, coverage/integrity warnings, thermal and frequency-cap
   evidence. Interrupted/incomplete evidence must remain visible as such.
4. Export both session CSV files and confirm **selected session IDs and
   timestamps** correspond to the runs; verify rows are not mixed.
5. From Sessions choose Run A as baseline for Compare, including a session
   outside the newest 20 if available. Select B. Check distinct IDs persist
   across tab switches/rotation.
6. Choose independent full/preset/custom monotonic windows, calculate DUKO
   A/B, then change the selected candidate while a TXT/CSV export picker is
   open. Export must contain the **original frozen** A/B report, not new
   selections.
7. Compare interpretation must remain descriptive telemetry with qualified
   missing/insufficient evidence; **no FPS, causality or game-power claim**.

## D. Controlled interruption and repeatability

- Test denial of overlay and notifications; recording remains usable where
  Android permits foreground operation, with truthful error messaging.
- Simulate a Service/process interruption **only after backing up or
  exporting valuable recordings**. An unfinished original must not be
  silently replaced. Recovery must preserve original session identity.
- Repeat with a second session to confirm no sticky HUD, double sampling,
  stale Quick Settings tile, or stuck START/STOP button.
- Optional endurance: one 30–60 minute idle and one 20–30 minute gameplay
  recording; note app responsiveness, temperature trends and battery use.
  These durations are test targets, not validated performance guarantees.

## Evidence package for each failure

Save the app version and exact source SHA, Android build, Odin mode, steps
to reproduce, expected vs actual UI behavior, one screenshot (sensitive
content removed), Diagnostics TXT, relevant session CSV and DUKO TXT/CSV.
Record whether the bug reproduces in debug and leanDebug.

If using `adb logcat`, collect only the necessary time window and sanitize
personal data before sharing; logs can contain unrelated app information.
Do not share hardware serial numbers, account IDs or credentials.

## Sign-off conditions

- [ ] M1.6 exact-commit GitHub build + JVM tests green
- [ ] M1.6 exact-commit standard and optimized API 35 tests green
- [ ] Physical Odin 2 Portal UI / telemetry / HUD / Quick Settings pass
- [ ] Two real sessions compare and export correctly
- [ ] Overlay-denial and interruption behavior validated
- [ ] No critical reproducible crash, duplicate HUD or evidence corruption
- [ ] Matching installable APK and checksum captured
- [ ] Outstanding defects recorded, owner/priority assigned

**Only after these boxes are evidenced** should M1.7 be called accepted.
RC1 additionally requires release-signing, migration and longer stability
work. Do not merge or publish from this checklist alone.
