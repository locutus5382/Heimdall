# Bifrost M1 UI architecture — implementation contract

## Mission and non-goals
A handheld-first, offline Android observatory. One Activity, one recorder service, one database, one HUD overlay. The Bifrost UI replaces the presentation layer; it never creates a second recorder, changes hardware frequencies/fans, invents FPS, or claims battery-terminal energy equals game power. Preserve old session data and Android permission handling.

## Information architecture
- **Dashboard** is the default launch. Primary recording status and START / STOP & SAVE. Latest available sampled metrics in a compact grid (unknown sensors display —); HUD opt-in state and overlay permission, context Setup shortcut, HUD Studio shortcut, last saved session → Sessions. No historical analysis, long tag editor, comparison report, raw diagnostics, duplicate app banner, or obsolete alpha copy on Dashboard.
- **Sessions** is history and evidence-qualified per-run analysis. Reuse SessionHistoryPanel, DukoTagEditor on expanded run and CSV export. One independently scrollable area. Expensive DB/DUKO operations run off Main.
- **Compare** provides saved-run A/B selection, independent time-window presets/custom seconds, DUKO descriptive analysis and TXT/CSV export. Reuse DukoComparisonPanel with off-main evaluation, never infer causal performance or FPS.
- **Blades** are contextual, single-pane overlays. Setup (DukoTagEditor next-run/current-run), HUD Studio (HudProfileStore-backed controls and user overlay toggle), Run Picker (contextual Compare entry and recent run identifiers without a second comparison panel), Diagnostics (RuntimeDiagnostics and TXT export). Every blade has a visible touch path, Close/Back handling, accessible title and scrollable contents. Controller L1/R1 cycles tabs while navigation owns focus, X opens context blade, Y opens HUD Studio, B/Back closes blade. Never capture focused text editors.
- **Navigation shell** owns tab and blade state; MainActivity owns recorder/service/Android permission callbacks; leaf screens are presentation-only. Preserve navigation state across ordinary Activity recreation.

## Layout tokens and behavior
Midnight #0B1425 background; Editorial #111C31 header/surfaces; Blade #132038; Cobalt #192B49; Signal #65D5F5 recording/action and focus; Amber #F0BD62 caution; Coral #F16B78 errors; Ice #F2F6FC main; Muted #94A7C4 secondary. Flat surfaces, minimal nesting, no blur shader. All interactive targets at least 48dp. Wide landscape screens: top toolbar and compact dashboard section, multiple metric columns, right blade 42% width. Narrow: wrap or stack actions/metric cards, blade ~88% width. Avoid scrolling nested in unbounded containers.

## State ownership and actual API links
- MainActivity onCreate INSPECT / foreground START and STOP: HeimdallRecorderService action intents; progress and last sample: RecorderStatusHub.state, sealed RecorderStatus.
- HUD opt-in: existing SharedPreferences heimdall_ui_v1.hud_enabled; permission Settings.canDrawOverlays and ACTION_MANAGE_OVERLAY_PERMISSION. HUD layout/dock/scale/lock: HudStudioPanel/HudProfileStore.
- Current-run/next-run tags: DukoTagEditor(sessionId), backed by DukoSessionContextStore; labels are USER_DECLARED, not detected hardware settings.
- Historical sessions: SessionArchive.recent, overview, dukoFoundation; SessionHistoryPanel; CSV via SAF.
- A/B: SessionArchive.dukoMonotonicBounds / dukoCompare via DukoComparisonPanel; TXT/CSV via SAF.
- Diagnostics: RuntimeDiagnostics through DiagnosticsPanel, export through SAF.
- Quick Settings service remains separate; UI reflects its actual RecorderStatusHub status rather than duplicating it.

## Buttons and expected actions
Dashboard Start -> existing START callback when Idle/Finished or no outstanding session; Stop & Save -> STOP when Recording/NeedsAttention owned session; during Stopping/Saving buttons disabled to prevent contradictory requests. HUD switch -> change opt-in preference; if enabled with no grant, open Android overlay permission; never claim HUD is visible merely because preference is true. Setup -> SETUP blade; HUD Studio -> HUD_STUDIO; Last session -> Sessions tab; Diagnostics -> DIAGNOSTICS. Shell Dashboard/Sessions/Compare tabs switch one content slot and close any blade. Compare Run Picker -> open a compact contextual selection blade with jump-to-Compare; actual A/B picker remains only in Compare. Close and Back return to underlying tab. An absent permission/sensor presents neutral unavailable state.

## Milestone acceptance
1. One combined core-based M1 feature branch with screen wiring and no engine/database rewrite.
2. Existing production code compiles in debug, leanDebug, release/releaseQa; exact-head push and PR CI and API35 Android instrumentation pass before merging.
3. Real recording Start/Stop/INSPECT, HUD permission, tags, Sessions analysis and Compare exports remain functional. A focused text editor does not activate controller navigation; Diagnostics is reachable via touch.
4. No duplicate Dashboard history/DUKO compare/Studio/tag panels. Database and heavy analysis operations off the Main dispatcher.
5. Generated APK artifact and exact SHA with installation caution. Device-specific Odin acceptance remains NOT TESTED until manually exercised.

## Implementation order
A. Replace legacy Dashboard and switch MainActivity to HeimdallTheme and BifrostShell. B. Consolidate navigation/touch and scroll/blades; retain existing module slots. C. Patch history and comparison IO behavior. D. Update tests for new navigation and focused controller interactions. E. CI and hardware-ready deliverable. No auto-merge, no deployment.
