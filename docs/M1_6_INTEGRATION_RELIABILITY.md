# Heimdall M1.6 — integrated workflow reliability

**Scope:** Feature branch `feature/m1-bifrost-ui-rebuild`, draft PR #54.
M1.6 follows M1.5's touchscreen-first Bifrost UI. It is a *reliability and
workflow-continuity milestone*, **not** a claim of Release Candidate 1.

## Changes and ownership

1. **Native recorder remains the single recording authority.** No second sampler,
   synthetic FPS, root, sysfs writes, changed database schema, or network access.
   Dashboard, HUD, notification and Quick Settings continue to observe the
   recorder's actual state; dispatch is not treated as successful recording.
2. **Finished session handoff:** Dashboard's `REVIEW SAVED SESSION #id` calls
   `SessionScreenViewModel.focusSession(id)` and navigates to Sessions.
   The ViewModel forces a fresh SQLite archive read before expanding the
   exact requested row, rather than opening an arbitrary cached session.
   A missing row is reported; no guessed ID is substituted.
3. **Out-of-band recording:** Sessions has explicit `REFRESH` for recordings
   saved by Quick Settings/HUD or another application lifecycle. One refresh
   job is active at a time; previous reads are cancelled on forced refresh.
4. **Historical archive discovery:** Both Sessions and Compare load the newest
   20 completed sessions initially. Touching `LOAD OLDER...` expands the
   read limit in increments of 20 up to 100, respecting the existing
   `SessionArchive.recent(limit)` DB safeguard. Existing expanded session
   evidence and selected A/B IDs remain owned by their Activity ViewModels.
   This is deliberately **not unbounded scrolling or full-database paging**.
5. **A/B double-tap guard:** `CompareScreenViewModel.compare()` sets `busy`
   before dispatching its IO coroutine, so two touches cannot start two
   comparisons. Changing a selection still cancels stale analysis; reselecting
   the existing A/B run is a no-op. `canCompare` now checks that both
   distinct session IDs are present in the loaded, completed archive.
6. **Optimized QA compatibility:** Lean-debug only R8 rules preserve the
   `androidx.compose.foundation.ScrollKt` public ABI linked by the separately
   compiled touchscreen instrumentation APK. Production release R8 remains
   unmodified.

## Automated M1.6 acceptance

Run the exact HEAD through the existing workflow:

- Android debug, leanDebug, release-equivalent builds; pure engine tests
- Standard Android API35 instrumentation, including native recorder round-trip
  and Dashboard / HUD / Sessions / Compare / Diagnostics
- Optimized Android API35 instrumentation with matching test counts
- New M1.6 integration tests:
  - **stop-to-review:** exact newly finished ID opens and retains its DUKO
    destination through Activity recreation, even with older cached history
  - **manual reload:** a newly saved Quick Settings session appears after tap
  - **rapid compare:** two fast taps execute exactly one bounded A/B comparison
  - **history pagination:** older sessions load in batches while expansion
    stays anchored to the original session
  - **A/B pagination:** comparison selection remains valid during paging
  - **review CTA:** finished recorder UI forwards the actual session ID

No PR merge, RC label, or public release on a merely passing build. An
optimized test failure still blocks a green CI gate, even if it is a QA ABI
mismatch rather than a demonstrated app crash.

## Physical Odin 2 Portal acceptance still required

- Touch launch, font scaling and landscape/portrait layouts on device
- One >5-minute idle recording and two matched gameplay recordings
- Start/stop via Dashboard, HUD and Quick Settings; all controls agree on
  actual state and only one recording/HUD overlay exists
- Stop/save, tap `REVIEW SAVED SESSION`; the exact new row opens with
  measured sample count and any integrity/coverage caveats
- Perform two DUKO A/B analyses using user-declared test conditions and
  independent monotonic windows; exported TXT/CSV contain the correct
  session IDs and evidence warnings
- Save sessions while app is backgrounded, return to Sessions and refresh
- Exercise disabled overlay permissions, denied notifications, Android
  process recreation and interrupted recording recovery
- Verify controller shortcuts do not steal touchscreen focus or keyboard
  input, without requiring a controller at any point

## Outstanding before RC1

Document real Odin screen captures, long-run recorder/HUD stability,
permission behavior and measured overhead. Iterate on concrete failures;
do not introduce speculative hardware claims or expand modules until
these acceptance criteria have evidence.
