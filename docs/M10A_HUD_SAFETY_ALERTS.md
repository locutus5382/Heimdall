# M10a — Evidence-aware HUD thermal and sustained-load warnings

## Scope
Pure Android-free `HudSafetyAlertEngine` evaluated once per new monotonic telemetry sample, never in Canvas drawing. Profiles and overlay visibility must not restart timers. A new session resets state; duplicate or out-of-order samples do not advance durations. Gaps greater than 4 seconds break all duration streaks; unknown or invalid measurements never count as safe or as valid high-load evidence.

Default observational thresholds, **not universal OEM safety limits**:
- Battery warm >= 38°C, clear after 30 seconds below 37°C.
- Battery hot >= 42°C for 15 seconds, clear after 60 seconds below 40°C.
- Battery persistently hot >= 45°C for 30 seconds, clear after 60 seconds below 43°C.
- Android OS status MODERATE/SEVERE/CRITICAL+ directly provides important/high/critical severity. Light is not an alert.
- CPU or GPU utilization >=95% for 120 seconds becomes **informational** only, never a safety or FPS claim; clear under 90% for 10 seconds.
- Battery sensor disappearing after a warning yields a short `BAT TEMP ?` caution instead of an implicit safe conclusion.
- The most important active warning is drawn in the existing Canvas in all four HUD styles, including when battery temperature is not a chosen readout. No new WindowManager window, vibration or audible alerts; HUD touches continue through the existing lock/unlock ownership model.

### Deferred, independent follow-up milestones
- Device-calibrated CPU/GPU temperature trends and evidence-aware clock-cap/thermal correlations (no causal throttling attribution).
- Structured alert on/off/resolved event persistence, bounded timeline and DUKO report references; do not retrofit or mutate raw telemetry rows.
- User-configurable threshold profiles, persistent notifications, optional sounds and cooldown settings, Bifrost HUD blade controls.
- Physical Odin 2 Portal acceptance: overlay bounds/layout in all orientations, touch pass-through and UNLOCK at severe status, real thermometer provenance, hysteresis under gaming load. **NOT TESTED.**

CI tests exercise samples, monotonic timing, hysteresis, sensor absence and session reset; not a substitute for physical device testing. No automatic CPU, fan, performance mode or OS changes.
