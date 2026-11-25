# Bifrost 2.0 — Visual language and Odin layout contract

25 November 2026. Based on the user's four dark-UI reference images and CoreMetrics' previously useful telemetry density.

## Translation of the four references

1. **Charcoal dashboard/card collage:** near-black background, quiet cards and borders, compact graph strokes and precise typographic hierarchy. Do not use floating decoration without useful state.
2. **Black/yellow fitness screen:** one dominant action, compact summary cards and clear visual hierarchy. Use Heimdall cyan for the main action, *not* the reference's yellow.
3. **Black/green vehicle screens:** small status tiles and direct navigation. Use measured recorder state; do not pretend Heimdall controls performance profiles.
4. **Charcoal multi-function dashboard:** slim navigation rail, restrained gradient emphasis and modular cards; one main scroll owner, not nested page-sized scrollers.

## Brand tokens

| Role | Color | Meaning |
|---|---|---|
| Canvas | #0B0C10 | Near-black charcoal, not the old navy |
| Rail | #101319 | Compact persistent navigation |
| Surface | #171B22 | Regular opaque card |
| Raised | #222833 | Selected/action surface |
| Divider | #282E37 | Subtle 1dp boundary |
| Primary text | #F5F7FA | Clear metric typography |
| Muted text | #A2ADBB | Captions |
| Secondary muted | #788797 | Explanation |
| Signal cyan | #66D5ED | CPU, primary action, selected tab |
| Amber | #F1B262 | GPU, qualified evidence, second action |
| Teal | #55CDB8 | Ready, healthy and local |
| Coral | #FF7382 | Recording or real warnings |

Keep luminous accents below roughly 15% of the visible scene. Avoid full-screen colorful backgrounds, giant glowing borders and glass HUD text fighting game content. Accent colors always encode meaning.

## Odin 2 Portal landscape hierarchy

- 94dp compact left rail with >=48dp targets; preserve safe Android insets.
- **Live:** small heading; 315dp recording/test-condition column; remaining space for six metric tiles and two measured instantaneous CPU/GPU utilization bars.
- **Analyze:** selected run and duration, headline KPI cards, actual DUKO time-series BEFORE long lists, concise two-column metric summary; direct Minutes, Cores, Policies, Thermals, Events and Insights.
- **Runs:** one clear row per recording with sample count and duration; one touch opens its DUKO evidence.
- **Compare:** compact baseline/candidate controls and evidence-gated numeric results; report export and remaining visual comparisons still belong to R2.
- **Tools:** diagnostics and (later) rebuilt test context/HUD Studio. Placeholder labels must not masquerade as working buttons.
- Portrait uses stacked columns; never hide critical recorder STOP behind a rail or off-screen.

## Behavior

- UI presses 120–160ms feedback maximum; HUD is static with no continuous effects.
- Render unknown values as an em dash, not a fabricated zero. FPS/game power are not inferred.
- Charts use existing DUKO bounded original timestamped traces and their continuity flags. Never bridge a missing-data interval.
- The rejected HUD is disconnected. New micro-pod (~240×48dp), ribbon and compact card need independent 16:9 screenshots before implementation.
- Only the authoritative recorder Service may set START/STOP success. Dashboard, Quick Settings and notification must agree.

## Acceptance status

As of the first visual pass: charcoal palette, 94dp rail, two-column LIVE, compact surfaces, and DUKO evidence grid are implemented in ui/v2. This is a **design alpha**, not approved or device-tested.
Still required: finishing all analytics/workflows, exports, new test context, controller interaction, independent HUD 2.0, stable signing and screenshot-based real Odin acceptance.
Capture real Odin screenshots at standard and enlarged font scales, in portrait and landscape, including dark/bright game scenes for the HUD. Reject clipped content, overlarge cards, hidden analytics, touch traps and misleading missing sensor values.
