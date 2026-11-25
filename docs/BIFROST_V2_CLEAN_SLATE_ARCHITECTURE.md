# Bifrost 2.0 — Clean-slate product layer

**Decision, 25 November 2026:** reject the existing Compose shell, oversized HUD,
session accordion and nested DUKO presentation. Rebuild from a clean
presentation architecture. Preserve the independently tested recorder,
telemetry adapters, SQLite schema v8, session records, DUKO calculations,
comparison/report data models, durable START/STOP and Quick Settings behavior.

## Immutable boundaries

- **Preserve:** `engine/` Kotlin calculations, `NativeRecorderEngine`,
  `AndroidTelemetrySource`, `LocalRecordingStore`, `HeimdallDatabase`,
  `SessionArchive`, `DukoSessionAnalyzer`, core evidence integrity rules,
  recovery semantics and all existing recordings.
- **Reusable adapters, not visual layouts:** existing activity-scoped
  `DashboardViewModel`, `SessionScreenViewModel`, `CompareScreenViewModel`,
  `DukoMultiRunViewModel`, `DiagnosticsViewModel` and their repositories.
- **Replace:** MainActivity compositional tree; `HeimdallShell`,
  `BifrostDashboard`, `SessionHistoryPanel`, dense nested analysis
  panels, fragmented tabs/blades; `HeimdallCanvasView`, HUD layouts and
  on-screen interaction. Old sources are retired only when no references
  remain and their important tests are migrated.
- **Safety:** no parallel sampler, no second recorder Service, no re-created
  session ID, no FPS estimation, no cloud, no root. Existing reports remain
  available via their data adapters as the new UI is built.
- New UI work is **NOT** RC1 and must never be merged into the hardware-tested
  branch before usability acceptance.

## Navigation contract — landscape-first, responsive

A single compact persistent navigation rail (72–84 dp landscape), or small
bottom navigation on narrow portrait, carries:
1. **LIVE** — current recorder phase, primary START/STOP, live readings,
   test-configuration summary, session marker, latest completed run.
2. **ANALYZE** — immediately visible, selected recording + headline KPIs,
   real chart(s), evidence coverage, selectable time range; nested
   analytical modes: OVERVIEW, CORES, THERMALS, EVENTS, INSIGHTS.
   One-minute windows and CPU policy tables live here, not in Sessions.
3. **RUNS** — compact saved recording library with one-row summaries;
   selecting a run changes the selected analytical ID, not the recorder.
4. **COMPARE** — direct A/B and 2–6-run choices, report exports.
5. **TOOLS** — test conditions, HUD Studio (after new HUD implementation),
   device diagnostics, permissions and about.

- Content scrolls as one bounded surface. No giant top header or nested
  full-height scroll containers; button hit areas >= 48 dp while visual
  controls remain compact.
- Selected run is shared between RUNS and ANALYZE. No analysis is calculated
  until requested; stale async generations cannot change selected run.
- Invalid/missing values use em dash. A/B confidence and provenance labels
  must not be hidden behind cosmetics. No graph displays synthetic FPS.
- Controller navigation must not intercept focused text fields; device
  back should return to prior content or close a modal.

## Dashboard layout — information hierarchy

Landscape 1024dp+: small heading + 250–320dp left control column,
remaining width for two compact metric rows and an evidence strip.
Portrait: compact recorder card, 2-column metrics, scrollable content.
At rest show latest run and a direct ANALYZE affordance.
Critical STOP remains in the main screen, Quick Settings, and notification.
Do not use oversized decorative cards to simulate useful density.

## DUKO analytical screen

The *first screen after selecting a run* must show:
- session name, length, samples, status and time scope;
- 4 concise evidence cards: CPU load median/p95, GPU utilization,
  battery/thermal readouts where available, confidence/coverage;
- real 2-second time series (no fabricated/zero-filled gaps), mini chart
  and min/max timestamps when calculable;
- visibly labeled tabs for TIMELINE, CORES, POLICY, THERMALS, EVENTS and
  INSIGHTS; selected time range;
- export and compare actions accessible without navigating away.

The Kotlin engine remains sole owner of calculations and claims.

## HUD 2.0 replacement contract

Old HUD styles are rejected; they are not templates.
- Default **micro-pod**, approximate 240×48dp at typical Odin landscape:
  REC state + one primary measured value + session elapsed.
- Optional **micro-ribbon** and **compact detail card** occupy at most
  a small corner/edge. Expansion happens on explicit tap, not by default.
- Never overlay the app's own Bifrost navigation; hide HUD while Heimdall
  is foreground, without stopping telemetry.
- Keep one WindowManager owner. A failed remove keeps window ownership
  until confirmed detached; never spawn a second Canvas.
- Locked HUD is click-through with an independently reachable unlock
  target. STOP remains available from Quick Settings / notification.
- Themes must be designed in dp and previewed on the actual 16:9 Odin
  screenshot at multiple font scales before coding extra skins.
- No extra polling; the HUD uses `RecorderStatus` snapshots.

During the clean-slate shell transition, the previous HUD is **disabled**,
rather than offering a broken legacy theme. Do not represent a non-existent
HUD as configurable or enabled.

## Implementation increments

- **R0** (this PR): freeze/source architecture; new independent shell,
  compact LIVE screen, directly accessible ANALYZE and RUNS routes; no old
  shell or HUD mounted. Build/instrument integration.
- **R1:** new DUKO analytical workspace with complete chart, minutes,
  cores/policy and thermal/event integration, confidence, selected ranges.
- **R2:** compare and reports in new presentation, real test conditions,
  diagnostics, export and controller focus.
- **R3:** independent HUD 2.0 micro-pod, layout preview, touch lifecycle
  and OEM WindowManager fault injection.
- **R4:** visual acceptance on Odin, performance benchmark and regression
  testing; remove remaining dead UI files and stale tests; only then
  consider an integrated RC path.

## Acceptance of R0

- Engine/source APIs and DB unchanged; old v0.8.7 tagged branch survives.
- New navigation uses a single screen selection and no overlay.
- From RUNS, one tap opens ANALYZE for the selected completed session.
- Missing sample/thermal/PSI data are not silently converted into zero.
- Source view model cancellation and retained session selection preserved.
- CI may still require test migrations and old UI purging before R0 is
  declared green. Source integration must not be called Odin validated.
