# M9b — GPU clock residency historical adapter

This milestone wires the M9a pure Kotlin clock-residency accumulator to the
existing DUKO historical SQLite analysis cursor. It does not add polling, schema,
permissions, network calls or recorder overhead. The query continues to be
bounded to a requested monotonic interval and runs on Dispatchers.IO.

The report carries `gpuClockResidency` with absolute MHz bands
(<=200, <=400, <=600, <=800, <=1000, <=1200, >1200).
Each valid same-source adjacent interval within the cadence budget is assigned
to the preceding observed clock. Unknown intervals remain uncovered. GPU clock
is not GPU utilization, FPS, performance or evidence of thermal throttling.

GPU provenance is not independently validated in this adapter, so supported
coverage can at most yield QUALIFIED evidence; missing clocks yield INSUFFICIENT.
The new value is report data only, not yet a UI chart. Existing comparison-only
DUKO exports remain unchanged.

Acceptance: Android instrumentation uses a completed 2-tick session with 500
then 700 MHz, expects 2 seconds covered, 200 MHz upward transition and
QUALIFIED evidence, while GPU load remains INSUFFICIENT. Run exact-head
push and PR CI (engine, Android, R8, permissions and API35 instrumentation).
No Odin physical validation is claimed.
