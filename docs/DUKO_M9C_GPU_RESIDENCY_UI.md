# M9c — GPU clock residency in session details

After expanding a completed session and tapping ANALYZE, the historical DUKO
report displays GPU clock residency from M9b's bounded SQLite analysis.

The presentation reports covered and unmeasured fractions of the **requested
monotonic window** separately; nonempty absolute-MHz bands show fractions of
that same window, not fractions of valid readings. Transitions and largest
changes are counts/magnitudes of qualified adjacent samples only. A zero
coverage result displays 100% unmeasured rather than implying idle GPU.

No new sensors, polling, permissions, raw database writes, or single-session
document exports. The DUKO Option B comparison-only TXT/CSV report policy
remains unchanged.

Validation: Android instrumentation checks partial and missing coverage.
CI does not prove physical Odin 2 Portal GPU clock source correctness,
overlay behavior or game performance. Verify source provenance, recorded
cadence, device temperature and overlay behavior on the actual handheld.
