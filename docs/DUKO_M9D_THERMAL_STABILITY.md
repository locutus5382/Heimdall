# M9d thermal stability foundation

Stacked on verified M9c PR #46 exact head e70f98f487caa26ce080b4df00a8652fa09314c2 (push 44970811927 and PR 44970825521 successful).

This is an Android-free, opt-in, bounded-memory engine component, not yet wired to SQLite or UI. It compares fixed 120-second early and late phases of a >=240-second selected window, using sample-held temperatures for contiguous, same-source, valid intervals. At least 80% coverage and eight intervals per phase are required. A source/cadence discontinuity or unverified provenance prevents a qualified delta. A late slope within ±0.10 °C/min is labelled an **approximate plateau observed**, never thermal throttling or fan efficacy. Phase means are time-weighted. A late slope is a weighted regression over qualified interval midpoints, distinct from the existing DukoThermal two-pass regression, which remains unchanged.

Caveats: Fixed early/late intervals do not establish matched gameplay activity, and plateau is only a descriptive signal. There is no new sensor polling, SQLite migration, permissions or recording behavior. Follow-up adapter/UI and physical Odin 2 Portal tests are separate milestones. No device validation claimed.
