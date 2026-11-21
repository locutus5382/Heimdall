# M6b — user-initiated DUKO A/B document export

## Behavior

The DUKO comparison panel now offers **SAVE TXT** and **SAVE CSV** only
after two distinct completed sessions have produced a comparison report
and both selected monotonic windows are available.

Each button takes a snapshot of the already computed, evidence-gated
M6a report and launches Android's Storage Access Framework
`ActivityResultContracts.CreateDocument` picker. The user chooses
the destination; the app writes UTF-8 through the returned content URI
on `Dispatchers.IO`, with visible success, cancellation or failure
feedback. No broad storage permission, internet permission, root,
external sharing service or app-private export directory is required.

The proposed filenames include the two numeric session identifiers.
The app never silently overwrites a previously saved file. The provider
may rename a conflicting file according to Android's SAF semantics.

The original recording rows and metadata remain untouched. A canceled
picker never writes. Pending export content is cleared when the picker
returns; changing the comparison afterward cannot silently alter the
already requested document.

## Evidence boundaries

- Only the M6a comparison-only Option B format is exported.
- No single-session DUKO report, inferred FPS, game performance winner,
  causal driver claim or wall-power interpretation.
- Non-comparable deltas remain blank or N/A as enforced by the tested
  engine formatter.
- User-declared fan, performance and ClusterTune settings are not
  automatically verified hardware states.
- This implementation does not claim Odin 2 Portal physical testing.

## CI and physical acceptance

Run engine unit tests, full Android build and R8 matrix, merged manifest
permission audit, standard and optimized API35 instrumentation and APK
launch smoke. Before release, manually verify both document pickers,
cancellation, provider write errors, exported UTF-8 contents and
landscape/overlay behavior on the Odin 2 Portal.

This is a stacked draft PR on M6a. Do not merge automatically.
