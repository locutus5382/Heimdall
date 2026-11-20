# Heimdall F0 native recorder acceptance

This document records what must be **observed**, not merely implemented, before F0
may be marked complete. F0 is not a public release. Legacy CoreMetrics remains untouched.

## Automated gates (both required)

1. JVM engine tests and standalone Android debug APK:
   `gradle :engine:test :app:assembleDebug --no-daemon`
2. Fresh disposable Android emulator (API 35) with the Compose UI acceptance test:
   `gradle :app:connectedDebugAndroidTest --no-daemon`

The emulator test presses the actual dashboard START and STOP & SAVE buttons,
waits for samples persisted on disk, attempts a duplicate START via the
service, then inspects SQLite for **exactly one COMPLETED session**, samples
attached to that session, and a non-null end timestamp.

The GitHub workflow has two jobs. A source-code test is not a PASS until
the corresponding workflow run completes successfully. The integration test
runs only in the emulator job, not as part of `:engine:test`.

## Physical Odin 2 Portal gate (required after emulator)

Use an F0 APK **only from a successful CI run**. Keep CoreMetrics installed;
Heimdall is a separate app ID and does not import old recordings.

1. Launch Heimdall, grant notification permission, and confirm the initial
   state reads "Ready to observe".
2. Tap START in the dashboard. Confirm Android's foreground notification and
   that "samples on disk" increases during at least 60 seconds of gameplay.
3. Verify any unavailable CPU/GPU metrics display **—** rather than 0 or
   implausible guessed values. Basic sensors depend on ROM/kernel permissions.
4. Press HOME, return to the game, then reopen Heimdall. Recording must
   continue as the same session, without a duplicate recorder.
5. Tap STOP & SAVE. Verify the app reports "Session saved" with a nonzero
   persisted sample count and the foreground notification closes.
6. Repeat a normal second recording (new session ID). Confirm START and STOP
   operate again. Observe and record any `NeedsAttention` diagnostic code.
7. Record Android version/ROM, permissions requested, number of samples,
   unavailable metrics, and any crashes. Screenshots are useful.

**Out of scope for F0:** automatic crash restoration, session history,
advanced analytics, DUKO reports, exports, Quick Settings and HUD. Do not
represent process-death recovery as solved just because a normal STOP passes.

## F0 rejection / rollback rules

- START fails, UI stays idle while the service collects samples, or the
  dashboard is inconsistent with recording status.
- STOP cannot finalize the original session, or duplicate START creates
  multiple open sessions.
- SQLite rows or session end timestamps disappear, or a supported metric
  is silently recorded as zero when its kernel source is inaccessible.
- App crashes, unintended background recording occurs, or sensitive/network
  permissions are introduced.
- Any automated gate fails, has not run, or cannot be verified.

When any condition occurs, keep the change on its feature branch and fix
the failing behavior before merging or advancing into F1.

## Next phase

F1 adds validated native process-death recovery, STOP fault injection,
sessions and diagnostic export. It must preserve single-engine ownership and
must never manufacture a replacement recording.
