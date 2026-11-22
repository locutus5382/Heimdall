# M9a: clock residency foundation and integrated APK gate

## Integrated installable candidate (before M9a)
Verified PR #42 exact SHA `617bfc158b3b38fa946eb6009eda6752801580cd`.
Both [push 44950870487](https://github.com/locutus5382/Heimdall/actions/runs/44950870487)
and [PR 44950883698](https://github.com/locutus5382/Heimdall/actions/runs/44950883698)
completed successfully, including API 35 Android instrumentation, R8 and permission checks.
PR workflow published **HEIMDALL-v0.7.0-duko-debug** artifact ID 14726055503
and **HEIMDALL-v0.7.0-duko-lean-debug** artifact ID 14725931027.
The names reflect existing workflow naming, not the M8 milestone version.
Use GitHub's artifact download and verify its SHA/digest before installing.
Do not uninstall an existing build containing unexported recordings.
Physical Odin 2 Portal gates in `docs/M7C_INTEGRATED_ACCEPTANCE.md` remain NOT TESTED.

## M9a scope
Android-free, bounded-memory absolute MHz-band clock residency accumulator.
One accumulator per policy/source or GPU; callers must not mix policy identities.
Counts only adjacent valid same-source observations at <= 2x expected cadence
(or cadence+1s, whichever is greater). Uses previous observed clock as a
sampled step-function estimate; it does not claim clocks stayed constant
between observations. Uncovered time is explicit. Reports transitions and
largest rises/drops without inferring CPU load, FPS, throttling or causes.
No new per-tick polling, UI or SQLite schema changes.

**Not yet integrated:** bounded SQLite selected-window adapter, per-policy
identities, normalized hardware-maximum bands, UI, exports and device checks.
Do not present these statistics in the app until provenance/coverage tests
and integration are complete.
