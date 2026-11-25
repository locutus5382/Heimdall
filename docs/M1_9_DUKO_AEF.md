# Heimdall M1.9 — DUKO Batch 2 (A → E → F)

## Protected baseline and scope
- Exact development parent is M1.8 PR #55, successful exact-head SHA `d369ec36eeb62783b9f9cf32fc6ff9fd49a40fb3`. M1.8 is a separate **unmerged** branch.
- The validated v0.8.0 test baseline `950cfc606aeca80e4db04f265632b7bf6fdec423` remains unchanged.
- This branch is `feature/m19-duko-intelligent-analysis`, alpha version `0.8.2-duko-insights-alpha` (versionCode 11), **not RC1**.
- All computations are deterministic and retrospective from the existing single SQLite cursor; no additional sensors, polling, database mutations, FPS assumptions, or user-cloud traffic.

## A — Automatic workload segmentation
- Uses contiguous **60-second M1.8 blocks**, no interpolated samples. Partial blocks or absent usable CPU and GPU signals become UNKNOWN.
- A load signal qualifies at >=80% continuous time coverage and non-INSUFFICIENT evidence.
- Heuristic minute thresholds: ACTIVE if CPU mean >=25% or GPU mean >=30%; candidate high load if CPU >=65% or GPU >=75%, **SUSTAINED_LOAD only if two or more contiguous full high-load minutes**. IDLE requires *both* CPU and GPU mean <=15% with adequate evidence; missing GPU never becomes 0%.
- RECOVERY describes a lower-load minute immediately after observed ACTIVE or SUSTAINED_LOAD. It is **not proof of a cooling trend**. Intervening missing data prevent a recovery label. Values between idle and active thresholds are UNKNOWN.
- Adjacent same-phase minutes coalesce into timestamped segments. Evidence is the weakest contributing minute, flags identify incomplete sources; all classifications remain experimental.

## E — Comparison-window suggestions
- Only complete contiguous ACTIVE / SUSTAINED_LOAD minutes can be suggested.
- At least two adjacent minutes (>=120s); >=85% coverage of each usable CPU/GPU signal; within-minute sample SD <=24 percentage points.
- Adjacent time-weighted means may differ by at most 12 percentage points for CPU and 15 for GPU. Missing a sensor in one minute but not another breaks comparability.
- Up to three **non-overlapping** candidates, ranked by duration, minimum coverage and start time. There is no hidden 'similar FPS' filter.
- The UI explicitly warns that matching game scene, emulator, graphics driver, device mode/fan, fan/thermal state and recording conditions remain necessary for a reliable A/B conclusion.
- Confidence remains QUALIFIED when GPU readings are unavailable or sensor provenance is not validated. No automatic cross-run workload-equivalence assertion.

## F — Evidence-qualified DUKO findings
- Finds sustained sampled load, recorded prolonged CPU/GPU utilization or PSI pressure, sustained observed lower CPU-policy maximum, and qualified warming slopes where available.
- Every finding carries typed evidence, an observed time window when available, and a separate limitation.
- Policy ceiling < hardware ceiling **does not establish why it occurred**; a persistent cap may be preset-like, but never automatically assigned to ClusterTune or thermal throttling.
- Thermal slope is a sensor trend, not causal evidence. PSI is system-wide; it is not a per-game frame-time metric.
- Interrupted/recovered sessions cannot produce SUPPORTED findings. Empty sessions produce an explicit insufficient-evidence conclusion instead of 'everything normal'.
- Event timeline caps and the M1.8 first-360-minute bound are disclosed.

## UI integration
Existing Sessions → DUKO Analysis adds **INSIGHTS** beside MINUTES, CPU POLICIES and EVENTS. Sections A/E/F display phase labels, timestamps, minimum observed coverage, candidate windows, finding limitations and sensor uncertainty. These are read-only recommendations; selectable range chart interactions remain Batch 3 (G/H).

## Validation gate
- JVM tests cover sustained/active/recovery boundaries, missing GPU (not idle), stable 3-minute candidate, noncontiguous gaps, unstable adjacent-minute rejection, evidence-qualified caps/PSI findings, interrupted downgrade, and bounded 360-minute analysis.
- API 35 touchscreen test covers INSIGHTS access and content; existing M1.8 / selected-window Android tests continue to run.
- Debug and leanDebug R8 build plus instrumentation must pass on **this exact commit**. Passing engine tests alone is not acceptance.
- Hardware calibration of workload thresholds, recording overhead K, battery/thermal signal availability and repeatability on Odin 2 Portal are still needed.
