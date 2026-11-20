# HEIMDALL v0.5 — M4 Canvas HUD & APK size acceptance

This is an **alpha**, not a confirmed Odin build. Compare the same-commit
unshrunk debug APK with the R8-optimized `leanDebug` APK from GitHub Actions.
A debug-signed APK is NOT a release build; CI signing keys can change.

## Why v0.4 exceeded 20 MB

The measured v0.4 APK is **20,579,418 bytes**, 19.63 MiB. The largest
`classes.dex` was ~15.39 MiB, `classes6.dex` ~3.44 MiB, and the total resource
table ~0.39 MiB. There were no bundled native `.so` libraries. This points to
unshrunk AndroidX/Compose bytecode, not UI artwork, as the main contributor.

## What changed

- R8 code minification and resource shrinking enabled for the release variant.
- Additional `leanDebug` variant uses R8, `shrinkResources`, an optimized
  ProGuard default and a temporary debug signing certificate. CI retains the
  normal unshrunk debug build for instrumentation and debugging.
- CI writes a comparison of actual byte sizes, uploads both QA APKs and
  exercises app launch after installing the optimized package on API 35.
- One original Canvas renderer covers the four visual presentations:
  Micro-Gauge Strip, Compact Glass Card, Horizontal Ribbon and Vertical Spine.
- `HudRenderState` formats validated readings outside drawing; empty metrics
  display —. The recorder remains the sole source of truth.
- `HudLayoutEngine` measures actual label/value widths before painting,
  clips metric count to space available, and allocates explicit 48dp touch
  targets in an expandable control tray, rather than overlapping metrics.
- 60-point CPU-load history with missing-data gaps, not a fake FPS graph.
- Conservative thermal indication: battery-temp amber 38–42°C, prolonged
  >42°C becomes coral only after 15s. Never label as proven throttling.
- Docked Ribbon supports top/bottom and Spine supports left/right.
- Locked overlay queries Android's maximum pass-through obscuring opacity,
  leaves the original independent UNLOCK chip and preserves Service ownership.
- Repeated recoverable draw failures disable the HUD, not the recording.

## Automated acceptance

- [ ] JVM tests green including HUD presentation caps, bounded history,
      sanitized profiles and missing values.
- [ ] Normal debug and minified leanDebug APKs compile from the **same commit**.
- [ ] Optimized APK is smaller than unshrunk APK; record exact byte delta.
- [ ] Android 15 instrumentation green for four Canvas layouts and
      existing service/SQLite/CSV behavior.
- [ ] Optimized APK installs and its Activity launches in emulator.

## Odin 2 Portal acceptance

1. Back up completed Heimdall CSVs before changing builds. Do not uninstall an
   APK holding recordings without exporting first; debug signatures rotate.
2. Launch dashboard, grant overlay permission, start recording, then enter Eden.
3. Verify exactly one micro-gauge strip; tap ≡, MODE, and cycle
   Strip → Card → Ribbon → Spine → Strip.
4. Verify no clipped numerals or overlapping controls and no fabricated
   CPU/GPU temperatures, GPU load, power or FPS.
5. Change visible metrics, scale (0.85x–1.25x), opacity and bezel side in HUD
   Studio while recording. The same HUD should update without a new session.
6. Drag/snap floating styles; test top/bottom Ribbon and left/right Spine,
   landscape, portrait and system insets.
7. Lock. Verify game receives touches outside the separate UNLOCK chip;
   unlock, use STOP, verify original session and exported sample count.
8. Confirm QS tile, notification STOP, service interruption and recovery.
9. Run 30 minutes with HUD on/off. Compare UX and abnormal memory usage.

## Explicit non-goals of M4

No fake FPS, no unvalidated CPU/GPU temperature, no power W calculation,
no additional process, no native graphics-driver hook, no third-party GPL
source. The old CoreMetrics package remains untouched. Physical ROM-specific
touch pass-through and graphics performance remain *unverified* until tested.
