# M9f — thermal stability session display

Stacked on M9e PR #48, exact head `e59696c108dbbb8abb1a8eaa7f0b489553514053`, whose push workflow 44979969159 and PR workflow 44979976493 both completed successfully.

The saved-session analysis UI now shows M9e's historical early/late thermal phase results, including signed late-minus-early °C, phase coverage in seconds, late slope, and descriptive approximate plateau where qualified. Insufficient provenance or coverage yields an explicit insufficient-evidence message, never a numeric thermal delta. The fixed phases are **not** matched gameplay segments; neither clock nor thermal behavior establishes throttling, fan effectiveness, FPS or performance.

This UI change does not modify the recorder, SQLite, sampling, permissions, or analytics algorithms. Three API 35 Android display regression tests check qualified display, insufficient provenance, and missing delta. Exact-head push and PR CI must both pass before acceptance. Real Odin 2 Portal testing remains outstanding.
