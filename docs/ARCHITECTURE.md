# HEIMDALL — Native architecture

## Why a clean repository

The earlier CoreMetrics trial reused its legacy MainActivity, status provider,
Quick Settings tiles and recording service. The trial could collect samples
through the **legacy** action while the Heimdall console failed its own status
contract. This project intentionally imports **none** of those classes.

### Non-negotiable product principles

- One native recorder lifecycle, one foreground service and one actual sampler.
- No root, Internet, account, upload, analytics SDK, background telemetry
  service or FPS claim. The user decides when to record.
- Read-only sensors: missing permission/kernel data is **unavailable**, not zero.
- On-disk evidence precedes UI acknowledgments. Never start a replacement
  session when an unfinished original remains.
- Durable STOP, sampler cancellation/join, atomic row finalization, matching
  lease release. Retry never changes a previously completed end timestamp.
- UI, Quick Settings and eventual HUD are views/commands of **one** engine;
  none owns an additional recorder or database writer.
- Future PowerProfile and GPU/CPU capability probes are device-dependent;
  don't hardcode Odin 2 battery watt-hours or CPU policy clusters.

### Modules

- `:engine` — pure Kotlin domain types, coordinator, and JVM lifecycle tests;
  no Android framework, no content providers, no UI.
- `:app` — native Compose interface, one foreground Service, read-only
  Android sensor adapter, private SQLite database and STOP lease.

### Crash policy in v0.1

Automatic process-death recovery is **deliberately not advertised yet**. If an
open row or active lease survives service death, START refuses to create
another row and displays `UNFINISHED_SESSION`. A user can request STOP on
a matching durable lease, otherwise evidence remains for repair. This is
preferable to a fictitious recovery that silently changes engines.

### Progressive acceptance gates

1. **F0**: independent app compiles, pure engine tests pass, one functional
   Android START/sample/STOP round trip and crash/fault instrumentation tests.
2. **F1**: native process death recovery, durable STOP fault injection,
   session list, metadata and diagnostic export.
3. **F2**: device capability probe and optimized telemetry (thermal, PSI,
   clocks, clusters, GPU/battery/W), real Odin validation.
4. **F3**: one Quick Settings workflow, single-window HUD with three visual
   modes, touch-lock and controller-safe behavior.
5. **F4**: analytics, DUKO A/B reports, integrity grading, confidence-aware
   comparisons, CSV/TXT/PNG/PDF, selected ranges and session metadata.
6. **F5**: performance, UI polish, device portability, Odin/Retroid/AYANEO
   validation and published builds. Only after regression evidence retire
   obsolete CoreMetrics engineering work.

This plan is a scope definition, not a claim that F1–F5 are implemented.
