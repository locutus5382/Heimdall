# DUKO M4a — Selected monotonic analysis windows

A first, intentionally small performance improvement: the Android adapter can
analyze `DukoWindow(startMs, endMs)` without materializing the entire session's
11 metric observation arrays when only a short interval is requested.

- `SessionArchive.dukoFoundation(sessionId, selectedWindow = ...)` forwards
  to the same pure DUKO analysis and M3c confidence engine.
- On selected requests, SQLite checks that requested bounds fall within actual
  recorded nonnegative `elapsed_ms` extrema and reads only samples in the
  inclusive selected range. SQL retains `ORDER BY id` to expose out-of-order
  evidence to the engine (never silently sort timestamps).
- Selection outside recorded bounds, a session not completed, or a record
  without monotonic timestamps returns `null`. A valid *interior* interval with
  no samples produces a report with INSUFFICIENT evidence, not fictitious zeroes.
- The denominator is exactly `selectedWindow.durationMs`, including any
  unmeasured edges. No interpolation/extrapolation at selected endpoints,
  no bridging missing values, source transitions or large gaps.
- The no-window call preserves previous full-session semantics.
- Neither the rows nor the SQLite schema, indexes, app permissions,
  recorder lifecycle, HUD, battery calibration semantics, or in-memory
  analysis version (v2) changes. Selected-window reads reduce application
  allocation, but are **not** a claim of sublinear SQL work: SQLite must still
  locate matching rows, and `samples(session_id,id)` is currently the only
  relevant index. Benchmarking/index policy belongs to M4b.

Pure JVM cases verify exact window, fractional coverage, empty selection,
inclusive endpoints, and energy integral. Android emulator tests cover
SQLite round trip, session immutability, invalid bounds, interior-empty
interval and interrupted-session confidence.
