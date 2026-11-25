# HEIMDALL — Bifrost 2.0 master architecture and UX blueprint

**Design proposal | 25 November 2026 | FOR REVIEW — NOT AN APPROVED BUILD OR RC1.**

This is the end-to-end design contract for rebuilding the rejected Heimdall UI/HUD. It uses the user's four dark UI references for visual language and CoreMetrics for practical monitoring density, **without importing the old CoreMetrics recorder code**. Preserve the existing native recording engine, DUKO analytics engine, original recorded evidence, SQLite schema v8, session IDs, tests and exports.

The current Bifrost V2 R0 implementation is a partial prototype. Its last exact-head Android application/engine compilation succeeded, while Android UI instrumentation failed on Activity/navigation test expectations. This document sets the accepted target before further UI coding.

## 1. Product philosophy and boundaries

Heimdall is an offline, read-only Android handheld observatory: it explains what the device measured during a game or emulator test. It is **not an FPS/frametime meter, performance controller, game-smoothness benchmark, or root tool**.

Core principles:
- One recorder, one foreground Service, one persistence store, one canonical state stream; HUD, Quick Settings, notifications and Compose are separate views/controllers of that single engine.
- Never change original samples/timestamps to improve visual presentation. Tag/marker editing is separate from raw evidence.
- Missing, permission-denied, invalid or stale readings are unavailable, **not zero**. Source provenance and evidence quality stay inspectable.
- Driver, emulator, game, performance, fan and ClusterTune settings are USER_DECLARED until separately verified. A CPU ceiling below hardware max does not identify its cause.
- DUKO calculations are owned by the existing Kotlin engine. Compose may format, group and plot immutable results but never recalculate evidence/confidence in its draw loop.
- The default interaction model is touch-first Odin 2 Portal landscape; adapt to portrait and other Android handhelds. Protect physical screen safe areas and STOP controls.
- DUKO Report remains Option B: comparison PDF/PNG, A/B TXT/CSV, and session/raw or selected-range CSV; no unsupported single-session PDF claim.

## 2. System diagram and state ownership

~~~text
READ-ONLY ANDROID / KERNEL SIGNALS
     |
     v
AndroidTelemetrySource + SensorCatalog + BatteryEvidence + ProcSignalReader
     | DeviceSample (normally 2-second intervals; nullable, monotonic)
     v
NativeRecorderEngine -------> RecorderStatusHub ------> LIVE / QS / Notification
     |                                  |
     | durable append and STOP          +----> HUD 2.0 Coordinator (future)
     v
LocalRecordingStore / SQLite v8
     |
     +----> SessionArchive (paged headers, indexed ranges, raw CSV)
     |          |
     |          v
     |     DukoSessionAnalyzer (background, selected monotonic window)
     |          |
     |          v
     |     Immutable DukoFoundationReport
     |        stats / energy / thermals / PSI / per-core & policies
     |        frequency caps / residency / timelines / minute windows
     |        phases / suggested windows / events / insights / confidence
     |          |
     |          +---> ANALYZE screen (all tabs share one selected window)
     |          +---> DukoComparison / DukoMultiRun --> COMPARE screen
     |                                       |
     |                                  Visual document
     |                                 PDF/PNG renderer
     |
     +----> DukoSessionContextStore (user tags + START snapshot)
     +----> DukoSessionMarkerStore (user event notes, separate table)
     +----> DukoDeviceCapture (read-only device capability snapshot)

UI-only presentation adapters + ViewModels
  RecorderUiModel | SelectionCoordinator | AnalysisUiModel | RunsUiModel
  CompareUiModel | TestProfileUiModel | HudStudioUiModel | DiagnosticsUiModel
     |
     v
Bifrost 2.0 / Compose: LIVE - ANALYZE - RUNS - COMPARE - TOOLS
~~~

| State or action | Authoritative source | UI consumers |
|---|---|---|
| Recording phase, real session ID, persisted sample count | NativeRecorderEngine / RecorderStatusHub | Live, QS, notification, HUD |
| Source samples and timestamps | LocalRecordingStore / SQLite | SessionArchive and DUKO analyzer |
| Next-session user tags | DukoSessionContextStore draft | Live preview; Tools editor |
| Metadata and frozen capability snapshot at START | DukoSessionContextStore / DukoDeviceCapture | Runs, Analyze, Compare |
| Selected run ID | New shared SelectionCoordinator using SavedStateHandle | Runs, Analyze, Compare |
| Applied selected window | AnalysisUiModel, one immutable DukoWindow | Every DUKO chart, stat and export |
| Comparison drafts | Existing CompareScreenViewModel / DukoMultiRunViewModel | Compare only |
| Markers | DukoSessionMarkerStore | Event timeline and annotation editor |
| HUD state and layout | New single HudOverlayCoordinator and versioned profile store | Overlay and Studio preview |
| Recorder overhead | RecorderOverheadHub / RuntimeDiagnostics | Tools diagnostics on demand |

Keep existing ViewModels as adapters where their business behavior is correct. Consolidate screen-only state instead of creating another analytics pipeline.

## 3. Application navigation

Five main, first-class destinations in a slim landscape rail (target 84–96dp; scale by device) and a compact portrait bottom bar:

| Screen | Purpose | Main control |
|---|---|---|
| LIVE | Recorder, current telemetry and next test context | START / STOP & SAVE |
| ANALYZE | Complete selected-session DUKO workspace | Explore real evidence / range / markers |
| RUNS | Saved recording library, metadata and raw CSV | One-tap open ANALYZE |
| COMPARE | A/B, 2–6 run analysis and Option-B reports | COMPARE / EXPORT |
| TOOLS | Test profiles, HUD Studio, device diagnostics, permissions | Configure / inspect |

Navigation contract:
- Tap RUNS entry → immediately select that run and open ANALYZE.
- Tap COMPARE from ANALYZE → preselect the analyzed run as baseline A.
- Back closes modal/subview before leaving destination. Navigation does not touch recorder state.
- Avoid oversized top tabs, duplicated session accordions, giant in-app banners and stacked scroll containers.
- Keep tiny REC status globally and the full recorder state on Live; STOP must be reachable in Live, Quick Settings and notification.
- Selected range persists while moving among DUKO tabs; switching run resets or restores the correct run-specific range, never reuses a window belonging to another session.

## 4. Global visual system

Use the inspiration images for restrained dark surfaces, elegant hierarchy, subtle lighting and concise status cards, not for an unrelated fitness/yellow or vehicle/green rebrand.

| Visual role | Token | Use |
|---|---|---|
| Background | #0B0C10 | Near-black charcoal |
| Navigation | #101319 | Slightly raised compact rail |
| Card | #171B22 | Primary surfaces |
| Raised card | #222833 | Selected/focused controls |
| Divider | #282E37 | Thin separation |
| Main text | #F5F7FA | Large clear readings |
| Secondary text | #A2ADBB | Labels |
| Quiet explanation | #788797 | Sensor caveats |
| Cyan | #66D5ED | Primary action, CPU, navigation |
| Amber | #F1B262 | GPU and qualified observations |
| Teal | #55CDB8 | Ready/local/healthy |
| Coral | #FF7382 | Recording/actual warnings |

Mostly opaque cards, subtle border and optional low-key gradient. Accent illumination stays under roughly 15% of visible UI. No continuous blinking/shimmer. Buttons have 48dp minimum targets with 120–160ms in-app press feedback. Numerical readings are tabular and have accessible text equivalents. Meaning never relies on color alone.

Landscape target: slim navigation rail + usable content width. Default dashboard is a two-column design (control/profile column about 280–320dp, remainder for telemetry). Portrait stacks columns, retains a permanently reachable STOP. Every page has ONE vertical scroll owner; long lists are lazy.

## 5. LIVE screen — exact layout and actions

~~~text
+-- NAV --+---------------------------------------------------------------+
| H       | HEIMDALL / LIVE                          ● REC 04:21  LOCAL   |
| LIVE    |---------------------------------------------------------------|
| DUKO    | +---- SESSION CONTROL ---+ +---- MEASURED TELEMETRY --------+ |
| RUNS    | | #23   128 samples      | | CPU %    GPU %    BAT %         | |
| A/B     | | [ START ] [STOP & SAVE]| | CPU MHz  GPU MHz  BAT TEMP     | |
| TOOLS   | +------------------------+ | CPU utilization ━━━━━━        | |
|         | +--- TEST CONDITIONS ---+ | GPU utilization ━━━━━         | |
|         | | Game / emulator        | +--------------------------------+ |
|         | | Driver / fan / tuner   | +--- LATEST RUN / EVENT STRIP ---+ |
|         | | [EDIT] [HUD STUDIO]    | | #22 [ANALYZE]  warnings...     | |
+---------+---------------------------------------------------------------+
~~~

**Session Control:** Idle, Preparing, Recording, Stopping, Saving, Finished, NeedsAttention. Show real ID, sample count, monotonic elapsed time, error/recovery. Disabled START during active or unresolved session, and never claim completion until durable STOP. Quick Settings uses the same recorder identity.

**Telemetry:** six default cards CPU%, GPU%, battery%, representative CPU clock, GPU clock, battery temperature; optional CPU/GPU temperatures, memory, PSI, OS thermal status via compact More Signals inspector. Each value includes unit, unavailable state and optional source explanation. Instantaneous bars are accurate to the latest stored sample; don't invent a continuous trace when recording is off.

**Test context:** user-declared game, emulator, driver, performance, fan, ClusterTune on/off/Unknown/profile, notes. Edit Next Test goes to Tools. START freezes draft plus device capability profile for the new session, without changing past metadata.

**Latest run:** direct Analyze; recent qualified event/alert strip; live marker action only when a session exists. HUD Studio deep link is distinct from recorder START.

## 6. ANALYZE — first-class DUKO workspace

~~~text
+-- NAV --+----------------------------------------------------------------+
|         | DUKO   RUN #23   04:14  128 ROWS   QUALITY: QUALIFIED           |
|         | [FULL] [MIDDLE 80] [LAST 50] [SUGGESTED] [CUSTOM] [APPLY]      |
|         | [CPU median] [GPU median] [CPU peak] [Energy evidence]          |
|         | +-- TIMELINE: REAL SAMPLES, GAPS, EXTREMA, EVENTS ----------+   |
|         | | CPU (%) ~~~~     GPU (%) ~~~      + cursor + range       |   |
|         | +-----------------------------------------------------------+   |
|         | Overview | Cores | Clocks | Thermals | Pressure | Minutes       |
|         | Events | Insights                                               |
|         | MIN AT TIME  MAX AT TIME  MEAN  TIME MEAN  MEDIAN P95 SD        |
|         | SOURCE / COVERAGE / VALIDITY / WARNINGS                         |
|         | [ADD MARKER] [COMPARE] [EXPORT RANGE CSV]                      |
+---------+----------------------------------------------------------------+
~~~

The session selector stays small; run duration, context and outcome always visible. Show **actual gap-aware time-series before long text**. Kpis update only after the matching selected analysis completes. The right inspector on wide screens and bottom sheet on narrow screens shows sample timestamp, metric source, validity, extrema and warnings.

Selected ranges: Full, middle 80%, last 50%, 1–3 automatically suggested stable windows, Custom dual-handle range with minimum 2 seconds, APPLY / RESET. A new selection invokes indexed SessionScreenViewModel.selectAnalysisWindow → DukoSessionAnalyzer, not a visual crop. Cancel previous analysis and discard stale generation responses. All visible graphs/tables/energy/events and CSV export use the same applied window.

### DUKO analytical tabs / underlying module contracts

| Tab | Existing calculation/source | Display and controls |
|---|---|---|
| OVERVIEW | DukoFoundationReport.integrity.metrics, timelines, confidence, context, energy | Headline KPI, gap-aware graph, min/max annotations, mean/median/P95 and evidence summary; tap KPI to focus a unit-consistent chart |
| CORES | perCoreTimelines + CoreActivityReport cores/clusters/busiest | 8-core heatmap or small-multiples, per-core mean/max/peak time, busiest-core distribution by observed leader time, cluster balance, selected core focus |
| CLOCKS & CAPS | CpuPolicyResidency, FrequencyCapTimeline, GpuClockResidency, saved CPU policies | Separate actual MHz traces, hardware ceiling and observed scaling ceiling, 400 MHz residency bands, transitions, capped/unknown duration; cap cause labelled UNKNOWN |
| THERMALS & POWER | ThermalAccumulator / ThermalStability / DukoEnergy / Android thermal | Battery, CPU and GPU measured temps with peaks and slopes; OS status, current/voltage/valid estimated terminal watts, discharge mWh/Wh and mWh/min; evidence caveats |
| PRESSURE & MEMORY | CPU PSI some avg10, memory PSI some/full avg10, available RAM | Separate same-unit PSI charts, pressure duration/coverage and available memory; system-wide, not game-specific. App PSS belongs in Tools |
| MINUTES & PHASES | DukoMinuteReport / DukoWorkloadReport / DukoSuggestionReport | Exact 60-second rows, partial-final flag, CPU/GPU median/P95/mean, thermal, energy, PSI coverage; phase ribbon IDLE/ACTIVE/SUSTAINED/RECOVERY/UNKNOWN and suggested intervals |
| EVENTS & MARKERS | DukoEventTimeline / FrequencyCaps / MarkerStore | Filterable timestamped events and live/post-run annotations, event-duration bands on chart, marker editor; markers never mutate samples |
| INSIGHTS | DukoInsightReport + DukoConfidenceReport | Max eight qualified findings, evidence grade, observed window and limitation per finding; link to exact chart interval and one-tap apply suggested range |

Chart semantics:
- X = monotonic elapsed source timestamp; crosshair snaps to nearest genuinely measured value.
- No dual axes mixing % with MHz/°C/W. Group CPU/GPU load (%), clocks (MHz), thermal (°C), PSI (%), battery level (%), current (mA), voltage (mV) and power (W) separately.
- Mark actual min and max at their recorded times; optional arithmetic or time-weighted mean line, P95 reference; label each distinctly.
- Treat invalid/stale/missing samples and discontinuity flags as gaps, never connect a false line to zero.
- Use original DUKO bounded signal traces; render/downsample only what is visible while preserving extrema/gap boundaries. Never alter SQLite rows.
- Large history: page events (250 max with dropped-count warning), page minute tables (first 360 global blocks, selected range for later data), lazy rendering; no 360 eager cards.
- Show source and quality grades: SUPPORTED, QUALIFIED, INSUFFICIENT. The code's baseline coverage gates are 95% / 80%; exceptions and unverified provenance downgrade quality. Do not display a numerical comparison from INSUFFICIENT data.

## 7. RUNS / session archive

A compact list, one row per run: ID, localized time, duration, source sample count, outcome, game/emulator/driver tags and quick quality summary. Row tap immediately opens DUKO analysis. Explicit short actions: Analyze, Compare as baseline, Export source CSV, Edit declared test context, Manage markers, Delete (with confirmation and source-protection constraints). No hidden expansion to reach analytics.

Search/filter target: game, date, driver, emulator, completion status; server-side bounded/paged history, no full sample scan. Selected run preserved across RUNS ↔ ANALYZE. Original sample timestamps and capability snapshots remain immutable after tag editing. Incomplete/recovered sessions clearly marked.

## 8. COMPARE / A-B and 2–6 runs

A/B:
1. Choose distinct A baseline and B candidate. Each gets independent Full/Middle80/Last50/custom monotonic window and visible time lengths.
2. Show declared context mismatch warnings BEFORE Compare.
3. Existing DukoComparison produces A and B time-weighted means, B–A delta and percentage if defined, with COMPARABLE / CAUTION / NOT_COMPARABLE per metric. If blocked, show DELTA BLOCKED; never replace null with 0%.
4. Unit-matched side-by-side charts align each window to relative elapsed time and explicitly state that equal relative time is not the same game scene.
5. Option-B export offers existing TXT, CSV, PDF and PNG with native SAF. Cancel does not write; frozen result prevents wrong-selection exports.

Multi-run:
- Choose 2–6 sessions, promote baseline A, use shared supported relative window presets. Compare per metric: qualified count, baseline mean, each candidate delta, median/min/max of valid deltas and descriptive direction INCREASE/DECREASE/FLAT/MIXED/INSUFFICIENT.
- Warn when tags/workloads/thermal/drivers differ, when evidence is insufficient and when trend cannot be established. No FPS, causal or "best graphics driver" claim.
- Existing multi-run visual export supports PDF and PNG only; do not advertise extra formats until implemented.

## 9. TOOLS / profile editor, HUD Studio, diagnostics

Tools has four clearly labelled subsections rather than a gigantic collapsed panel.

**Next Test Profile:** game; emulator; graphics driver; performance level; fan; ClusterTune enabled/disabled/Unknown and profile; tuning tool; free notes; one-session-only draft. SAVE NEXT RUN persists the draft only. START snapshots context plus DukoDeviceCapture. History-edit command changes declared labels, not original evidence.

**HUD STUDIO:** one-to-one preview of production HUD render primitives, choice of three styles, metric order/selection, scale, opacity, dock, snap position, drag lock, separate unlock position, permission status, Restore Defaults. Show/Hide is disabled until proper draw-over-other-apps permission and a safe window owner are present. Preview demo values must visibly say DEMO.

**Device & Sources:** manufacturer/device/SoC/SDK/kernel, discovered policy topology/limits, GPU source, CPU/GPU temp source, PSI availability, verified battery capacity if available, RAM, sensors and permission denial. Never hardcode Odin policy0/3/7, battery 30.8 Wh or infer a tuning preset from policy max.

**Diagnostics & App:** recorder latency median/P95 bucket bounds, longest read/SQLite/total tick, missed scheduler deadlines, read/write faults, process-wide CPU delta and on-demand PSS/native heap. Read-only row-integrity audit, lifecycle diagnostic TXT, permissions help, offline/privacy information, version/SHA, UI preference reset that does not erase recordings. Do not probe memory every 2 seconds.

## 10. HUD 2.0 / complete design and lifecycle

Retire the old oversized, semi-transparent HUD and its styles. Design three families using a new shared render model, with dp sizes verified on real Odin game screenshots:

| Mode | Initial footprint | Content | Behavior |
|---|---|---|---|
| MICRO PILL (default) | roughly 240–285 × 45–54dp | REC + elapsed + one or two selected measured signals | edge docking, one-tap compact drawer when unlocked, no constant animation |
| TELEMETRY RIBBON | roughly 340–450 × 46–62dp | REC, CPU, GPU, battery temp, optional clock | thin divisions, edge-bound, one-time expand/collapse |
| COMPACT GLASS CARD | roughly 230–275 × 108–148dp | 2×2 measured metrics, tiny real signal trace, compact safety strip | only explicitly expanded; 90–95% readable charcoal rather than game-obscuring blur |

Window state machine:
DISABLED → PERMISSION_REQUIRED → HIDDEN → VISIBLE_UNLOCKED → VISIBLE_LOCKED → RELEASING → HIDDEN. ERROR hides/retries safely without spawning a duplicate. **One overlay owner** (main Canvas plus separate reachable 48dp unlock chip), status sourced from RecorderStatusHub, never a second recorder or polling job.

- Locked main HUD is NOT_TOUCHABLE / passes game touches; the independent 48dp unlock chip stays tappable and clear of system gesture areas.
- Long press/drag only unlocked; snap to edge, clamp to actual bounds and persist normalized dp/anchor. L1/R1 game buttons must not be stolen by background HUD input.
- Main Heimdall Activity in foreground → hide the overlay without stopping recording; game foreground → restore if user enabled and permission/window state are valid.
- Failed WindowManager removal retains ownership and suppresses replacement until confirmed detached. Crash/restart cannot create a second HUD.
- No per-frame sensor polling, blur animation or Canvas-post callback storm. HUD updates from the existing recorder's sampled state.

Warnings reuse the existing HudSafetyAlertEngine (calibration provisional): battery warm ≥38°C; battery hot ≥42°C for 15s and persistent ≥45°C for 30s, with existing clear hysteresis; validated OS thermal status severity; CPU/GPU ≥95% for 120s is INFORMATIONAL, not proof of FPS loss. Missing temperature following an alert shows uncertainty rather than normal. Warnings alter a small stripe/outline only; no game-blocking warning dialog.

Quick Settings, notification and Live keep a working STOP even when the HUD is locked, hidden or has failed. HUD Studio previews and the actual overlay must share the same metric formatting/layout code, eliminating the previous mockup-vs-app mismatch.

## 11. Calculation contracts / source to UI mapping

| Concept | Correct numerical interpretation | Required UI |
|---|---|---|
| MIN / MAX and peak time | valid observations; recorded elapsed timestamp | numeric extreme plus chart mark and time |
| Mean | arithmetic mean of valid sample values | "Sample mean" |
| Time mean | time-weighted valid contiguous intervals | separate label and covered time |
| Median / P5 / P95 / P99 | observed sample distribution | exact unit and source, no FPS percentile |
| SD / variability | sample standard deviation | number, measurement unit and count |
| Evidence coverage | valid source-continuous interval fraction | coverage %, origin, SUPPORTED/QUALIFIED/INSUFFICIENT warning |
| One-minute windows | fixed 60s aligned to analysis range, partial final minute | per-minute row/heatmap, no bridging missing intervals |
| Busiest core | observed leader distribution per real core and cluster | per-core heatmap and measured share |
| CPU frequency residency | validated sample-derived clock time in 400MHz bands | bar proportions, unknown interval, policy topology |
| GPU clock residency | measured supported GPU clock source | histogram, source status |
| Frequency cap | observed scaling-max below hardware-max policy | onset/end/duration; cause UNKNOWN, preset-like only heuristic |
| Energy | signed current, battery status, voltage, unplugged and valid intervals | watts, Wh/mWh/min and warnings; not power attributable to game |
| Thermal slope | observed validated temperature trend | rate, range and peak time, never automatic throttling attribution |
| PSI | kernel CPU/memory avg10 system pressure | CPU/memory pressure charts and thresholds with system-wide caveat |
| Workload segments | heuristic IDLE/ACTIVE/SUSTAINED/RECOVERY/UNKNOWN over covered minutes | phase ribbon with experimental badge and evidence |
| Suggested stable interval | valid contiguous active ≥120s and coverage/variance checks | selectable suggestion, no proof that two runs match |
| Insight findings | supported, qualified or insufficient statement plus limitation | concise finding, source window, next investigation action |
| Events | timestamped observed transitions and sustained heuristics | synchronized timeline/markers and dropped-event count |
| Comparison patterns | quality-gated A/B and 2–6 descriptive deltas | show eligible count and context caveats, no winner |

No material analytic calculation lives inside Compose. Rendering only controls units, precision and visual axes.

## 12. Command and user-journey contracts

| Input | Backend action | User-visible feedback |
|---|---|---|
| START | DashboardViewModel → single Service / Engine | Preparing then actual Recording, real session ID |
| STOP & SAVE | Engine durable STOP + finalize | Stopping, Saving, then Finished or NeedsAttention |
| Quick Settings tile | TileDispatchGate → same Service | same recorder state, no duplicate |
| RUNS row | Shared selectedSessionId + SessionScreenViewModel.focusSession | ANALYZE of that exact ID |
| APPLY RANGE | DukoSessionAnalyzer via selected monotonic window | cancel stale requests, recompute all tabs, update coverage |
| ADD/EDIT MARKER | DukoSessionMarkerStore | marker table only, unchanged raw samples |
| SAVE PROFILE | DukoSessionContextStore draft | next session only |
| A/B COMPARE | CompareScreenViewModel / DukoComparison | signed eligible deltas and warnings |
| MULTI COMPARE | DukoMultiRunViewModel / DukoMultiRun | evidence-aware patterns and baseline |
| CSV EXPORT | SessionArchive.exportCsv | indexed raw/full window rows to SAF URI |
| REPORT EXPORT | DukoVisualDocument / DukoVisualRenderer | available TXT/CSV/PDF/PNG; cancellation safe |
| HUD SHOW/LOCK/DRAG | single HudOverlayCoordinator | only overlay state changes |
| DIAGNOSTICS | RuntimeDiagnostics on demand | read-only snapshot and local TXT |

## 13. Responsiveness, controller, safety and quality constraints

- Scroll: one main vertical scroll per destination; LazyColumn for large Runs, Minutes, Events. Horizontal scroll only bounded category/filter chips. No hidden buttons beneath persistent header.
- Chart: limit drawn vertices to visible resolution while retaining peaks/gaps; no db work or percentile calculation on UI thread or during Canvas drawing.
- Controller: focusable targets, D-pad/right-stick selection where supported, L1/R1 category tabs only inside Heimdall and never while editing text or gaming; Android Back closes the intended sheet.
- In-place update safety: preserve database IDs, ensure consistent signing and migration; if debug APK signing mismatches, do not erase the user's stored sessions to install.
- Recording: status never derived from UI optimistic clicks, no double sessions, unresolved rows respected, long-run sample integrity, Service crash and HUD exception isolation.
- Appearance: target standard and enlarged text; don't clip currencyless MHz/°C labels, don't rely only on color. Crossfade/press animation in app only; HUD static.
- UI tests must validate **semantics and real workflow**, not brittle old labels; exact-head standard and lean/R8 Android test jobs both pass, then real device screenshots and interaction testing.

## 14. Implementation plan and acceptance gate

**Design freeze:** agree the information architecture, exact screen content and chart/HUD interaction before another broad coding pass.

1. R0 Shell & stability: fix failing V2 API35 UI tests; align design tokens, shared selection and responsive navigation; keep rejected HUD disabled. Do not touch engines.
2. R1 Full DUKO presentation: implement all eight views, real gap-aware interactive plots, range selection, min/max timestamps, evidence/alerts, source inspector; verify known Odin CSV fixtures.
3. R2 Workflow completion: Test Profile editor, marker editing, CSV exports, A/B and multi selector/report actions, Tools/Diagnostics, controller navigation. No inert placeholder.
4. R3 HUD 2.0: produce actual three-style preview layouts on Odin screenshots first, then independent coordinator/window ownership, shared Studio preview and unlock/drag behavior. Verify no duplicate or input capture.
5. R4 User visual acceptance + regression: native landscape gameplay, bright/dark scenes, tablet/portrait, battery/thermals, long recording, Service recovery, CPU/PSS overhead, exact-commit CI and stable APK update.
6. RC1 only when R4 passes and verified branches are integrated without source migration/data loss.

**Later, not blockers:** median-absolute-deviation/outlier multi-run repeatability, device behavior fingerprint, persistent alert on/off events, single-session PDF, broader device validation and automatic OEM setting detection where legitimately possible.

**Definition of success:** Within one touch of a saved run, show useful DUKO charts and KPIs. Within two further taps, reveal any core, policy, battery/thermal, pressure, minute, event or insight view. The in-game HUD is small, legible and safe. All source data/quality limits remain honest. The preserved recorder continues regardless of which UI component fails.
