# Heimdall F1a — bounded recovery of the original recording

This is a **partial F1 implementation**, not proof of full process-death
recovery. It intentionally carries no CoreMetrics classes into Heimdall.

## Behavior

- When the dashboard opens, it asks the native service to INSPECT the
  original persisted session. INSPECT never starts sampling.
- When a session is marked RECORDING but no STOP intent was committed before
  process death, the dashboard displays `UNFINISHED_SESSION`. START stays
  blocked; STOP & SAVE is offered for the original lease/session ID.
- When the lease contains an already committed `stop_requested=true`, the
  engine may retry finalizing **that same session ID**. No replacement row is
  created, and the SQLite `ended_at_ms` is not changed on retry.
- When a STOP could not be recovered, the original database row and lease
  remain untouched for a user-initiated STOP retry. A missing lease is not
  silently fabricated.
- Multiple START controls still route to a single service and engine.
- Metrics are read-only; none of the code requires root or Internet.

## Acceptance

1. Kotlin unit tests: persisted STOP on restart finalizes original ID;
   unfinished session remains blocked until user STOP; finalization failure
   preserves row/lease and can be retried.
2. Existing F0 Android UI test: real dashboard controls produce exactly one
   completed SQLite row and persist samples.
3. Manual device interruption: record, kill the app process (not force-stop),
   reopen the dashboard, confirm original ID is shown as unfinished. START
   must stay disabled; STOP & SAVE must finish the original row. No duplicate.
4. Interruption after a durable STOP but before SQLite finalization must
   recover the same original row. This requires controlled fault injection
   before release; normal STOP is not proof of this race.
5. Confirm foreground service behavior on Android 13/15 and Odin firmware.

## Known limitations / next tasks

- A recording without a durable STOP is NOT automatically resumed after a
  process death; user must finalize it. This is an explicit safety decision.
- A missing lease with an unfinished SQLite row currently requires a
  deliberate repair protocol; START is correctly blocked.
- There is no user-accessible history, export, crash journal or recovery
  decision UI yet. F1b must add diagnostic inspection and durable fault tests.
- The status projection is in-process; restoration is initiated on dashboard
  launch, and full OS lifecycle behavior requires actual device testing.
- Do not merge solely because tests exist. CI and physical Odin tests must
  pass, and F0 acceptance remains prerequisite.
