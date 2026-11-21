# M7b — HUD WindowManager recovery

This change is stacked on CI-green M7a (PR #33, `ef9414f`).

## Failure addressed

An overlay window can become invalid after a display/permission/window-manager transition. Previously `updateViewLayout` logged an exception but retained the cached `view` and `params`; later telemetry ticks kept attempting to update the same invalid window, leaving the HUD absent until the Service restarted.

The controller now releases its window ownership on an update exception and applies the existing 3-second creation cooldown. Recording continues independently of the overlay. A partially initialized `addView` path also removes an already attached Canvas window before clearing references, avoiding a leaked window during setup failure.

## Verification gates

- Run all engine, Android build, R8, permission, API35 and optimized instrumentation checks at the exact commit.
- On physical Odin 2 Portal: start recording, show HUD, rotate / change display geometry, revoke overlay permission and restore it, lock/unlock the HUD, stop recording, and confirm only one Canvas HUD plus the optional unlock chip exists.
- Simulate window rejection on a device/instrumented WindowManager seam in a later test harness; normal emulator instrumentation cannot reliably force system overlay rejection.
- Verify SQLite sampling continues and STOP completes even when the overlay is unavailable.

No changes to database schema, recording engine, release R8, permissions, or telemetry semantics. Physical Odin validation remains **unverified**.
