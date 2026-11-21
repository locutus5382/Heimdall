# M8a — Offline emulator shortcuts

The existing DUKO test-condition editor now offers curated emulator name shortcuts.
Selecting a shortcut populates the existing free-text **Emulator and version** field;
users can append exact build/version information or type any emulator name.

These are **user-declared tags**, not detected installed packages or verified
emulator versions. No network access, package enumeration, or extra permissions
are introduced. Saved-session metadata and next-recording drafts continue to use
the existing DukoSessionContextStore and USER_DECLARED provenance.

The linked Obtainium discussion #3439 describes a broad community app pack,
not an authoritative emulator-only catalog. The list is intentionally curated
and is not synchronized with external sources.

Acceptance:
- Choose Eden, edit to Eden (custom version), and save next-session draft.
- Reopen and confirm the exact string persists.
- Open a completed session, change emulator, and confirm raw SQLite samples
  remain unchanged.
- Enter an emulator absent from the shortcuts; it must remain saveable.
- Verify on physical Odin 2 Portal; automated CI does not establish hardware behavior.

Follow-up milestones: time-stamped configuration changes, evidence-qualified
frequency-cap detection, and arbitrary monotonic time-range selection.
