# Duko Analytics: D0–D2 foundation

**Status:** v0.7 development candidate. **Do not merge** until normal and lean Android tests pass and Odin observations are reviewed.

## Guarantees

- Offline, no root, no FPS claims, no unrestricted Android privileges.
- Duko is pure Kotlin in `:engine` for metric definitions and arithmetic. Android-only SQLite and hardware adapters reside in `:app`.
- Missing readings are `null`, not zero. Every result carries time coverage and evidence classification.
- Raw session samples are immutable. Editable labels are stored in `duko_session_contexts` (SQLite v4) separately from hardware snapshots in `duko_device_profiles`.
- Historical v1–v3 data remains intact through the additive migration. Legacy samples without monotonic timestamps are excluded from time-integrated Duko statistics; their existing historical summary is preserved.
- Current CPU policy limits indicate restrictions, not which app activated them. A named ClusterTune profile, graphics driver, performance level or fan setting is `USER_DECLARED` unless future firmware/third-party interfaces independently verify it.
- Any sensor not supported by a given ROM must be marked unavailable. Do not guess a battery energy capacity.

## How to record reproducible tests

1. Open Heimdall, find **DUKO · NEXT TEST PROFILE**, and enter your game/workload, emulator version, graphics driver, device performance mode, fan profile, and optional underclock/ClusterTune name and notes.
2. Save these conditions **before START**. The next session captures a copy, plus a versioned device snapshot.
3. Record and STOP normally. In **Sessions → Analyze**, inspect data coverage, P50, time-weighted mean and evidence. Expand a session to change its manual tags without rewriting sensor history.
4. For Odin 2 Portal, repeat the same track/scene with identical settings, noting cold/warm state. Do not assume that equal game titles imply equal workload.

## Current mathematical definitions

- `DukoWindow(startMs,endMs)` uses monotonic elapsed time and includes endpoint readings for integration.
- `p5/p95/p99`: nearest-rank percentiles of validated observations.
- `median`: center of sorted valid samples, arithmetic midpoint for even counts.
- `sampleMean`: unweighted arithmetic mean of valid observations.
- `timeWeightedMean`: trapezoidal interpolation **only between adjacent, valid, same-source readings** when their separation is within the maximum cadence tolerance (2× nominal or +1 second, whichever is greater).
- `coverage`: summed duration of those accepted adjacent intervals divided by the selected window's duration, clamped to [0,1].
- Unsupported values, unverified source identifiers, reversed timestamps, source switches and sampling gaps generate warnings or weaken evidence rather than substituting invented values.
- Evidence thresholds are provisional engineering defaults: at least 95% valid time coverage for supported (with trustworthy source/time); 80–95% qualified; below 80% insufficient. They are not scientific certification.

## Scope intentionally deferred

- Cross-session A/B or 2–5-session comparisons and confidence calculations.
- Thermodynamic causal interpretations, GPU-rendering performance inference, and policy-cap attribution.
- Battery energy and autonomy integration until current/voltage provenance and charging/bypass context are validated.
- PDFs and chart exports; **DUKO Report Option B** will be comparison-only.

## QA acceptance checklist

- `:engine:test` passes known-value, missing-data, timestamp, source and percentile fixtures.
- Normal Android emulator instrumentation tests: record→stop→persist→analyze, SQLite v1/v2/v3→v4 migration, editable user tags, and versioned device snapshot.
- **Lean/R8 optimized** APK passes the *same* Android tests, not only compilation or Activity launch.
- Verify APK size and app process overhead on Odin 2 Portal.
- On a real device confirm observed policy limits are distinct from user-declared ClusterTune/fan/performance tags.
