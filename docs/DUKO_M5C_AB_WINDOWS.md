# M5c — bounded A/B analysis windows

Stacked on CI-green M5b PR #29, commit c7fa0def.

## Changes

- The read-only archive now queries the minimum and maximum recorded monotonic
  sample timestamps for a completed session. No wall-clock estimate is used.
- A/B selection offers independent per-session presets: full recording,
  middle 80%, or last 50%. Each selected interval is calculated against its
  own recorded extrema, not another session's time origin.
- Explicit Compare starts analysis. Changing either preset invalidates the
  previous report; the UI shows exact monotonic endpoints and durations.
- No new SQLite schema, no recorder/service writes, no network, no root,
  no FPS inference and no claims of equivalent workloads.
- Sessions with missing or invalid monotonic ranges fail closed. Partial
  selections shorter than two milliseconds are rejected.

## Limits and acceptance

Presets are the first usable selection interface, not arbitrary trim handles.
The existing DukoSessionAnalyzer rejects out-of-range windows and produces
insufficient evidence for empty in-range windows. The A/B comparison engine
still gates deltas on measurement evidence and declared workload context.
The most recent history list bounds the available session selector.

Run full exact-head engine/build/R8/permissions gates and Android API 35
instrumentation (including the new window arithmetic tests) before advancing.
Odin 2 Portal hardware accuracy, UI ergonomics and thermal validation remain
unverified. Keep this stacked PR draft and unmerged.
