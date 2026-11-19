# Duko Analytics — Foundation D0–D2 (v0.7 alpha)

**Scope:** pure Kotlin measurement contracts, durable test-condition labels,
device profile snapshots and conservative per-metric statistics. This release
is not DUKO Comparison Studio, not a PDF report, and not a hardware controller.

## Why settings are tagged, not guessed

An Android app running without root cannot safely infer a third-party
ClusterTune preset from cpufreq limits alone. The active CPU policy max
may also be changed by the OEM, power profile, system thermal management, or
another application.

Heimdall separates the two forms of evidence:

1. **USER_DECLARED:** ClusterTune state/profile, fan mode, performance mode,
   tuning tool, driver, emulator, game and notes.
2. **VALIDATED_SYSFS:** detected CPU frequency policy limits and observed clocks.
   A lower policy ceiling is an **observed restriction** of unknown cause,
   not proof of a particular underclock tool or thermal throttling.

An undecided or unavailable state is **Unknown**, not **Off**. The profile
allows custom text because different handhelds use different profile names.
Heimdall does not change frequency ceilings or fan controls.

## Data persistence

- SQLite upgrade **v1/v2/v3 → v4** is additive.
- Session samples and historical IDs are not rewritten or dropped.
- `duko_session_contexts` stores editable tags independently of telemetry.
- `duko_device_profiles` stores the versioned capability snapshot taken at
  START (CPU policy topology, exposed limits/governors, device/SoC, kernel,
  firmware, sensor source paths, reported total RAM).
- Test-condition draft in `duko_next_session_v1` is copied atomically to
  each new session; changing the draft does not change past recordings.
- An old session without the new context or monotonic timestamps retains its
  original legacy summary. The Duko adapter does not invent missing data.
- Device profiles intentionally omit hardware serials and IDs; rated battery
  capacity remains unknown unless independently validated.

## Duko statistics v1

Measurements have a stable metric ID, unit, value, source origin, nullable
value and validity. The initial engine calculates sample P5/P50/P95/P99,
min/max, arithmetic mean, sample standard deviation, trapezoidal
**time-weighted mean**, valid interval coverage and warnings.

These are intentionally distinct:
- Sample percentiles describe the distribution of observed sample rows.
- Time-weighted means require two adjacent VALID readings from the same
  source with a sufficiently short interval.
- A gap longer than `max(expectedCadence+1s, 2×expectedCadence)` is not
  bridged. Invalid/missing rows break continuity.
- Missing, invalid, permission-denied and stale readings are **not zero**.
- An unverified source cannot earn a fully SUPPORTED result.
- Evidence levels: SUPPORTED (≥95% time coverage), QUALIFIED (80–95% or
  unverified provenance/other warnings), INSUFFICIENT (<80%, or too few
  readings). These are provisional engineering defaults, not metrological
  certification or gaming performance metrics.
- Short sessions may receive descriptive statistics but not conclusions
  about long-term thermal stability, FPS or battery autonomy.

No FPS, frametime, battery Wh, or power estimates are fabricated. Battery
current is reported with its sign, in milliamperes. Advanced energy
integration and comparisons belong to later milestones.

## Automated verification gates

- [ ] Kotlin engine tests pass including gap/coverage, percentile, source
      changes, out-of-order timestamps and declared-vs-observed tuning.
- [ ] Android v1/v2/v3 migration tests pass; existing session IDs and samples
      remain intact.
- [ ] Real SQLite session START automatically persists tags and a source-aware
      device snapshot. Changing later draft tags cannot rewrite it.
- [ ] A completed recording can be analyzed through Duko's deterministic
      per-metric statistics; unsupported GPU readings show INSUFFICIENT.
- [ ] Normal and R8-optimized QA APKs pass instrumentation and launch.
- [ ] APK size remains measured in CI.
- [ ] Hardware trial on Odin 2 Portal confirms actual CPU policy IDs/limits
      and evaluates possible source restrictions.

## Odin manual protocol

1. **Back up prior Heimdall recordings.** GitHub Actions signs debug QA APKs
   with runner-local keys that may change, preventing in-place upgrades.
   Never uninstall a build with unexported data.
2. Set game/emulator/graphics driver, Performance mode, Fan profile, and
   ClusterTune profile in **DUKO · NEXT TEST PROFILE**. Save conditions.
3. START recording and inspect CPU policy clocks/limits in Diagnostics.
   Do not claim a ClusterTune preset from those measurements alone.
4. Change in-session tags, end session, expand it under Recorded Sessions,
   verify the labels persisted and edit a historical driver/fan label.
5. Open Duko Foundation statistics. Confirm CPU/GPU and battery coverage,
   warning labels and accurate missing-data handling.
6. Repeat with ClusterTune off and on (only where the user deliberately
   configures it) and compare observed policy ceilings manually. Do not
   perform any root/sysfs write through Heimdall.
7. Verify HUD, QS tile, STOP/recovery and CSV remain functional.
8. Send sensor diagnostics and sample CSV if reporting unsupported metrics.

## Deferred milestones

D3 energy/thermal/workload analysis; D4 baseline-vs-candidate studies;
D5 comparison UI and DUKO Option-B reports (CSV/TXT/PNG/PDF); repeatability
and broad Android hardware validation. The release remains a draft until
tests and physical Odin acceptance have been completed.
