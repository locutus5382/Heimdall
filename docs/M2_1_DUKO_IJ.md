# Heimdall M2.1 — Batch 4: I (multi-run) + J (PDF/PNG)

## Versioning / branch isolation
- Branch `feature/m21-duko-multirun-pdf-png`, app `0.8.4-duko-multirun-visual-alpha` (code 13).
- Stacked on Batch 3 `feature/m20-duko-interactive-analytics-markers` PR #57, exact verified SHA `5b1c3d9e4522d2107b80e9578f173c204ef3b977`.
- The Odin-tested v0.8.0 baseline remains untouched; no merge into main or release candidate.
- Batch 3 exact-head CI has passed. This new branch requires its own passing build and device checks.

## I — Multi-run comparison
- One new mode inside **the existing Bifrost Compare destination**; no duplicate dashboard or second database owner.
- Select 2–6 distinct, completed saved sessions. First selected is baseline; **SET A** promotes an existing candidate into baseline position.
- Three reproducible per-run monotonic window presets: full recording, middle 80%, or last 50%. Independently select valid exact bounds; no wall-clock inference or clamping.
- Use the existing bounded `DukoSessionAnalyzer`, source-aware metrics, and `DukoComparison.compare` for every baseline-versus-candidate pair. No raw sample pooling.
- Per-metric numerical patterns include baseline time-weighted mean, per-candidate deltas, median delta, min/max candidate deltas, count of qualifying candidates, and direction. At least **two** qualifying candidates are required before assigning any direction (increase/decrease/flat/mixed). Insufficient or context-blocked readings are excluded, never treated as zero.
- Direction thresholds are purely numeric (+/-0.5% of baseline with tiny floor), NOT sampling uncertainty, performance improvement, game quality, FPS, game thread ownership or ranking.
- Fully offline, no root, no extra live monitoring threads. Max 100 archived session headers in picker.

## J — PDF and PNG (DUKO Option B)
- Preserve existing A/B TXT and CSV exports and add printable PDF and PNG buttons for **comparison-only** reports.
- Use `DukoVisualReport` to construct an Android-free immutable report from A/B or multi-run *already analyzed* evidence. A snapshot freezes before the Storage Access Framework picker opens. No changes to source session data or export contents if the user changes tags/runs while the picker is open.
- `DukoVisualRenderer` uses native Android `PdfDocument` and `Bitmap`/`Canvas` **without** network, screen capture, game frames, MediaProjection or external fonts.
- A shared layout/pagination scheme renders every comparison metric, per-candidate window, evidence quality, relevant context caveats and explicit scope warnings. Unavailable/blocked deltas are represented with a clear `DELTA BLOCKED` label. No fabricated numerical comparisons.
- PDF pages are A4-like at 595 x 842 points. PNG stitches the same bounded report pages at 2x scale for up to 6 pages, then uses 1.45x for longer reports (RGB565). An overlong document fails explicitly rather than silently clipping content (12 page bound).
- SAF file extensions and MIME types match the emitted bytes. Cancelled pickers do not write. Stream creation, PDF generation and PNG encoding run on `Dispatchers.IO`. No global storage permission.

## Required gates before Odin testing
1. Pure engine: reject invalid/duplicate/oversized run collections; verify mixed evidence, context-blocked deltas, unknown metrics, median/range and no winner.
2. Android: verify native PDF can be reopened via `PdfRenderer`, PNG pixels decoded, SAF MIME/contracts, and oversized export explicit failure.
3. ViewModel/UI: compare mode switching, 2–6 selection, baseline promotion, stale result protection, export freeze, cancel/export concurrency, backward compatibility with TXT/CSV.
4. Exact SHA standard and R8-optimized Android builds + API35 instrumentation pass.
5. Odin 2 Portal: inspect full PDF pages and PNG visual clarity, usability with controller/touch, long sessions and calibrated sensor limitations.
6. Monitoring overhead workstream K remains independent and required before RC1.

## Out of scope
Single-session PDF/PNG reports; FPS, input latency, gameplay smoothness, thermal-throttling causality, a game performance winner, automatic driver recognition or control of ClusterTune.
