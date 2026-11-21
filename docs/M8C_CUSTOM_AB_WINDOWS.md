# M8c — Custom A/B monotonic windows

Adds a fourth option, **Custom seconds**, independently for sessions A and B.
Users enter start and end offsets in whole seconds relative to each session's
first recorded monotonic sample. A and B need not have the same duration or
timestamp origin. Existing full/middle-80/last-50 presets remain unchanged.

Invalid, empty, reversed, overflowed or out-of-recording offsets fail closed
with an explicit UI error; the analyzer receives a DukoWindow only after
validation. Re-running COMPARE produces a new report and the existing TXT/CSV
export uses exactly the selected A/B windows. Editing inputs clears stale
comparison results. Raw telemetry and session context are not changed.

Android instrumentation covers independent origins, out-of-range values,
overflow, invalid input and near-Long.MAX_VALUE monotonic timestamps.
CI is required; physical Odin 2 Portal validation is still outstanding.

Current UI accepts integer seconds (not sub-second precision or drag handles).
A separate single-session report is not introduced by this milestone.
