# Heimdall M2.0 — Batch 3 (G + H): interactive analytics and annotations

## Version and separation
- Experimental source branch: `feature/m20-duko-interactive-analytics-markers`, `0.8.3-duko-workbench-alpha` (versionCode 12).
- Parent: M1.9 PR #56 (DUKO A/E/F). This is a **stacked development branch**, not a merge to main and not an RC.
- The previously validated Odin-installable v0.8.0 SHA `950cfc606aeca80e4db04f265632b7bf6fdec423` remains unchanged.
- M1.9 had an instrumentation failure from a test-only double invocation of `minutes.result()`; the same fixture correction is applied on M1.9 and M2.0. Every branch requires a passing **exact-head** full matrix.

## G — Range-aware interactive analytics
- A two-handle RangeSlider operates over the full session's saved **monotonic** elapsed_ms bounds. Range selection requires at least two seconds, is explicit (APPLY), and can be reset (FULL SESSION).
- Applied ranges trigger a second `DukoSessionAnalyzer.analyze(id, selectedWindow)` via `SessionScreenViewModel`, not a slice of approximate graph pixels.
- Query uses the SQLite `samples_session_elapsed_idx` index and streams the source readings; stale results are invalidated by per-session request-generation checks.
- All currently shown DUKO statistics, charts, event timelines, inferred phases and conclusions use the active analyzed window. Missing/invalid ranges remain unavailable.
- Suggested stable intervals from Batch 2 can be chosen via CANDIDATE chips.
- Four same-unit layered telemetry groups: CPU/GPU load (%), CPU/GPU clocks (MHz), battery/CPU/GPU thermal (°C), CPU/memory PSI (%). No false multi-axis comparisons.
- Highlight recorded event starts and user annotation timestamps. This overlay is a **location cue only**, not proof that an event caused a visible signal.
- CPU-core and CPU-policy cluster activity are shown as actual time-weighted utilization bars, with observed lead seconds and coverage, not inferred core affinity or game FPS.

## H — Markers and selected-range CSV
- SQLite schema v8 adds only `duko_session_markers` (FK session id, original elapsed_ms, label, creation wall time). Raw `samples` and `sessions` are unchanged.
- On-device annotations supported both **during recording** via a Dashboard button, timestamped at touch, and **after recording** via a labelled timestamp slider and delete controls.
- A marker is never inserted as a telemetry sample and never changes its source timestamps. Maximum 200 markers per run, label 1–120 trimmed characters, owner isolation on read/delete and validation against stored session state/bounds.
- Selected-range SAF CSV is streamed from the original `samples` table using indexed elapsed_ms predicates, retaining original sample ids, wall-clock `time_ms`, monotonic `elapsed_ms` and existing 30 telemetry fields.
- Inclusive range endpoints as per existing DUKO selected-window contract. CSV text fields are RFC4180-escaped for commas, quote marks and newlines; no invented rows or UTC conversion.
- The saved range is frozen before the SAF picker opens. Cancelling the picker writes no file. The full-session CSV action continues to work unchanged.

## Quality gates
1. Pure engine unit tests: full-window identity, original elapsed clock, nonfinite/out-of-bounds fractions, two-second minimum and gap ignorance.
2. API 35 SQLite test: live + post-run annotations, deletion, source sample count unchanged, exact three-row selected CSV, escaped comma-containing text, and outside-bounds rejection.
3. API 35 migration test: upgrade a synthetic v7 database to v8 while preserving existing row id, monotonic and original wall timestamps.
4. Existing Android v1-v6 migrations, normal debug and R8-optimized leanDebug instrumentation must all pass at exact head.
5. Odin handheld checks remain necessary for touch control ergonomics, replay of long sessions, imported legacy data, sensor coverage and workstream K monitoring overhead. No feature is declared test-ready before these gates.

## Constraints
- UI is read-only with respect to recorded telemetry, aside from separately persisted user markers.
- FPS, game latency, performance improvement, cause of a cap and ClusterTune attribution are outside the evidence contract.
- Marker labels may be displayed in the local app; selected CSV exports include only raw samples, not private notes unless a future explicit metadata-export feature is introduced.
