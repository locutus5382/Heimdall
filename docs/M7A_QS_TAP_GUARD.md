# M7a — Quick Settings rapid-tap serialization

Stacked on M6b draft PR #32, final CI-green commit a1c8ecf.

## Failure prevented

The tile previously launched one coroutine per tap. Two taps could both
observe Idle before the suspended SQLite unfinished-session check returned,
then each send START. Recorder Service guards are still authoritative, but
redundant commands risk confusing the visible tile state.

The new TileDispatchGate is acquired synchronously on the tile's main
thread before launching a coroutine. Subsequent taps are ignored until
the disk check, dispatch attempt and tile refresh finish. A finally
block releases the gate even on errors or early returns.

No change to the recorder Service, SQLite schema, overlay ownership,
permission model, or release R8. This is a defensive UI command gate,
not a replacement for Service-level idempotence.

## Acceptance

- Android instrumentation verifies 100 consecutive blocked taps, release
  and retry, and detection of an unbalanced release.
- Both complete CI workflows must pass on the exact PR head.
- Physical Odin 2 Portal checks still needed: rapid tile double-taps,
  locked-screen tap, interrupted-session recovery, FGS-denied retry,
  HUD single-instance ownership and process recreation.
