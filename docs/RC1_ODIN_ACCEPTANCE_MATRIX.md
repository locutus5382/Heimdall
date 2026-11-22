# RC1 Odin 2 Portal acceptance matrix (not yet physically tested)

This is a **manual device test plan**, not a certification. The source baseline is the draft integration PR #52; record the exact installed commit SHA, APK SHA-256, Android build, device firmware, and test timestamp before any result. GitHub Actions API 35 emulator success is not Odin hardware validation.

## Installation and evidence preservation
1. Download the APK only from a successful exact-head integration workflow artifact, verify its SHA-256 against the published QA record, and keep the original APK.
2. Back up existing session exports and application data before changing builds. GitHub CI debug signing keys may vary; an incompatible signature can prevent an in-place upgrade. **Never uninstall a data-bearing app simply to bypass the signature error.** Stop and report the error.
3. Confirm package name, requested permissions, and no INTERNET, root, FPS, MediaProjection, or automatic hardware-control capability. Grant overlay/notification permissions through Android Settings only when prompted.
4. Record device model (Odin 2 Portal), firmware, Android version, performance profile, fan profile, GPU driver, emulator, and ClusterTune state as manual tags. Unknown values stay unknown.

## Manual test cases — all NOT TESTED
| ID | Procedure | Required observation | Status |
|---|---|---|---|
| R01 | Start via app; wait 2 min; stop; reopen | Exactly one session, durable stop, monotonic samples, correct duration | NOT TESTED |
| R02 | Start/stop rapidly from Quick Settings; repeat 20 times | No duplicate recorder, overlay, orphan service or accidental restart | NOT TESTED |
| R03 | Start via HUD; lock/unlock, expand/collapse, change HUD style | One HUD, reachable 48dp unlock/control targets, no unwanted touch interception | NOT TESTED |
| R04 | Revoke overlay permission mid-recording; restore; rotate, sleep/wake | Recorder survives; overlay safely detaches/re-attaches once | NOT TESTED |
| R05 | Force-stop UI process while recording, then reopen | Session evidence remains consistent; no invented recovered samples | NOT TESTED |
| R06 | Run 20–30 min actual gameplay; include warm-up and idle periods | No recorder stall, runaway memory, duplicated HUD, or broken session | NOT TESTED |
| R07 | Capture thermal/battery/CPU/GPU data | Report available source and coverage; unknown CPU/GPU thermal provenance remains insufficient | NOT TESTED |
| R08 | Observe HUD alert threshold and Android thermal status when naturally occurring | In-view alert in all layouts; no clipping or control overlap; no false causal throttling claims | NOT TESTED |
| R09 | Create two sessions with different driver/fan/performance/ClusterTune tags | Tags persist offline and remain distinguishable in A/B | NOT TESTED |
| R10 | Select independent time windows in A/B and export TXT/CSV | Correct bounded ranges, counts, missing-data qualifiers, immutable raw rows | NOT TESTED |
| R11 | Navigate Dashboard, Sessions, Compare, blades using touch and controller | Back/focus recovery; readable text; no blocked controller/touch actions | NOT TESTED |
| R12 | Test with Bluetooth/wired audio, foreground/background transitions and screen-off | No unexpected overlay focus/audio interception or lifecycle corruption | NOT TESTED |

## Recording the result
For each test capture PASS/FAIL/NOT TESTED, exact APK SHA-256 and source SHA, device build, reproduction steps, timestamps, sanitized logs, screenshots, and an exported session when applicable. Compare session row counts and time windows before/after restart. A pass on emulator CI is **not** a pass on physical Odin. Stop the release candidate for any confirmed data loss, duplicate active recorder, unsafe overlay control, or misleading sensor confidence.

## RC1 deferred work
Persisted alert event timeline, PDF/PNG exports, speculative device control, advanced activity segmentation, and causal performance claims are outside frozen RC1 scope. Do not infer FPS, game power, thermal throttling cause, or fan efficiency from clocks or temperatures.
