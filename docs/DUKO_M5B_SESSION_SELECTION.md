# M5b — recorded-session A/B selection

Stacked on CI-green M5a PR #28, commit 5557f5b.

## Implemented

- A read-only SessionArchive.dukoCompare adapter loads two distinct completed
  sessions through the existing DukoSessionAnalyzer, optionally using two
  explicit bounded monotonic windows. No new SQL schema or telemetry writes.
- The original session-specific user context is read independently for each
  session; it is never promoted to verified device settings.
- A compact A/B selector is exposed in Recorded Sessions when at least two
  completed recordings exist. Explicit SET A / SET B and COMPARE controls
  avoid implicit or accidental comparisons.
- The comparison panel shows per-metric A/B time-weighted means, differences,
  evidence quality and source/context warnings, without inventing FPS or
  asserting a winning configuration.
- All data access is through the existing IO-suspending archive API.
  Changing either selection cancels the prior comparison coroutine.

## Limits

This first UI pass compares complete sessions; bounded window parameters
are supported by the adapter but not yet exposed in the selector.
There is no DUKO Option B PDF/CSV comparison export yet.
The interface is functional scaffolding, not final HUD/UI polish.
The selector displays the most recent sessions already loaded by history,
not the entire database. Android hardware acceptance on Odin 2 Portal
is not yet established.

## Acceptance

Require exact-head engine tests, Android build matrix, R8/ABI/permission
checks, and normal/optimized API 35 instrumentation and launch smoke.
Keep draft and unmerged pending CI and integration review.
