# Heimdall M2.2 — Batch 5 / K: recorder overhead and RC safety

## Branch and scope
- Branch: feature/m22-recorder-overhead-release-hardening.
- Parent: verified Batch 4 feature/m21-duko-multirun-pdf-png at commit ffd12af3db1c33b352b5d21a6cb1338d3cb6a809 (PR #58).
- Preserve the separately tested v0.8.0 Odin baseline. No merge or RC1 claim until all gates pass.
- No new sensor polling, root, FPS estimate, MediaProjection, network, or additional telemetry database columns.

## K1 — Time the existing sample loop
The single NativeRecorderEngine sampler measures the elapsed clock around its
existing sensor read and SQLite append. For each durable sample:
- read, append and total read+append durations, measured in milliseconds;
- lateness relative to the next scheduled sampling deadline;
- skipped deadlines attributable to prolonged tick duration;
- optional process CPU delta across the read+append window. This includes all
  Heimdall process threads and is NOT attributable exclusively to the sampler;
- independent failure counts for sensor reads and SQLite writes.

No new collector or high-frequency process is introduced. Monitoring failures
must never change successful recording, STOP, recovery, or durable ownership.

## K2 — Bounded in-memory statistics
- Three fixed histogram arrays (read, append, total); no per-tick lists.
- P50/P95 are approximate histogram UPPER BIN BOUNDS, not exact percentiles.
- Preserve exact maxima plus success, failure, lateness and skip counts.
- Store at most one active session and one latest frozen snapshot.
- On successful STOP, close the in-process diagnostic snapshot. A service
  interruption closes it without incorrectly finalizing the SQLite session.
- Never write this diagnostic trace into the user's game telemetry tables.
- Process restart may clear overhead diagnostics; persisted game data stays intact.

## K3 — User-controlled Diagnostics
On demand, display the bounded recorder timing snapshot and measured process PSS,
native heap and process-wide CPU time. This memory probe is NOT part of the
two-second sampling path or graphical HUD.

Interpretation limitations:
- Recorder latency is elapsed time for this app's sensor/SQLite work.
- Process CPU ms during sampling windows includes non-recorder app threads.
- App PSS includes Bifrost, reports, service, kernel readers and user interfaces.
- Scheduling lateness is NOT an FPS or input-latency measurement.
- Measurement intervals are not an independently calibrated energy meter.

## K4 — Tests and Odin evidence protocol
1. Pure JVM tests use deterministic virtual time for observer lifecycle,
   failure isolation, clock boundaries and per-session ownership.
2. A histogram stress test processes 200,000 synthetic samples without
   accumulating unbounded sample history.
3. Android instrumentation writes 80 synthetic samples into disposable SQLite,
   verifies each saved row and analyzer output, and logs write median/p95/max,
   total analysis duration, process CPU delta and before/after PSS.
   Correctness and a generous hang guard apply to CI; emulator time values are
   NOT certified hardware performance budgets.
4. Odin A/B comparisons: same game scene, graphics driver, performance level,
   fan profile, ClusterTune setting, device position and warmup. Test:
   A (game ON, recorder OFF, HUD OFF);
   B (game ON, recorder ON, HUD OFF);
   C (game ON, recorder ON, HUD ON);
   D (game ON, recorder OFF, HUD OFF) to inspect warmup/drift.
   Repeat and alternate order (ABBA when practical), five to ten minutes each.
5. Capture Diagnostics before and after each run, note battery temperature
   and ambient conditions. External adb meminfo/Perfetto may help isolate
   process CPU and memory when available.
6. Review flags are provisional, not pass criteria yet: recorder tick p95
   above roughly 10–20% of cadence; recurrent missed deadlines; growing PSS
   after warmup; reproducible process CPU/thermal increase; lifecycle leaks.
7. Measure instrumentation cost itself by comparing a build where the observer
   is disabled. Process CPU clock queries run twice per successfully written
   sample, but their own effect has not yet been calibrated.

## Required before RC1
- Exact-SHA full Android, pure-engine and API35 instrumentation success.
- Odin real-hardware test covering HUD-off/HUD-on, 5–10-minute runs and
  extended recordings, with no extra Service or duplicated overlays.
- Inspect exports (CSV, TXT, PDF, PNG) and six-run report pagination.
- Preserve source monotonic timestamps, sample IDs and established evidence
  qualification, battery, pressure and no-FPS constraints.
- Confirm acceptable power/CPU/memory impact on the Odin rather than
  extrapolating hardware targets from emulator wall time.

No fabricated gameplay performance verdict or device-level overhead certification
is claimed by these changes.
