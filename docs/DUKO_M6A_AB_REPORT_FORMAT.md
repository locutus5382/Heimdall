# M6a — DUKO Report Option B, pure export foundation

This milestone follows the fully green M5c selected-window comparison PR #30.
It introduces a deterministic Android-free formatter for TXT and CSV A/B
comparison reports. No single-session report is generated.

The export carries both session identifiers, independent monotonic window
bounds, overall and per-metric evidence grades, warning codes, units,
time-weighted means and permitted deltas. NOT_COMPARABLE metrics never
export numeric deltas even if an upstream object accidentally contains them.
Non-finite values are not serialized as measurements.

CSV text fields are quoted/escaped and spreadsheet-formula prefixes are
neutralized; signed numeric values remain numeric. Output uses Locale.US
and a stable metric order. No network or new Android permission is needed.

**Limits:** This is the pure report format and its regression tests only.
A separate UI/export milestone must wire these formats to Android's
Storage Access Framework or Sharesheet with explicit user action, without
broad storage permissions. The reports are descriptive, not FPS, causal
performance claims, wall power or validated sensor calibration.

Acceptance: full engine tests, Android build and API 35 instrumentation
on the exact commit. Remain draft and unmerged until all checks pass.
