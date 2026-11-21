package com.locutus5382.heimdall.engine.duko

/**
 * Bounded-memory clock residency for one CPU policy or GPU clock stream.
 * Band membership is based on absolute MHz, never on an active policy cap.
 * Only adjacent, valid, same-source observations within the cadence budget
 * contribute time; a gap or invalid sample contributes UNKNOWN time.
 * Clock values are held at the preceding sample for each qualified interval;
 * this is sampled residency, not a claim about unsampled hardware behavior.
 */
data class ClockResidencySample(
    val elapsedMs: Long,
    val mhz: Long?,
    val sourceId: String?,
    val origin: MeasurementOrigin,
    val validity: ReadingValidity = if (mhz == null)
        ReadingValidity.UNAVAILABLE else ReadingValidity.VALID,
)

data class ClockResidencyResult(
    val window: DukoWindow,
    val bandUpperMHz: List<Long>,
    val bandDurationMs: List<Long>,
    val coveredMs: Long,
    val unknownMs: Long,
    val transitions: Int,
    val largestRiseMHz: Long?,
    val largestDropMHz: Long?,
    val evidence: DukoEvidence,
) {
    val coveredFraction: Double get() = coveredMs.toDouble() / window.durationMs
}

class ClockResidencyAccumulator(
    private val window: DukoWindow,
    val bandUpperMHz: List<Long>,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init {
        require(expectedCadenceMs in 250L..60_000L)
        require(bandUpperMHz.isNotEmpty() && bandUpperMHz.all { it > 0L })
        require(bandUpperMHz.zipWithNext().all { (a, b) -> a < b })
    }
    private val maxGapMs = maxOf(expectedCadenceMs + 1_000L, expectedCadenceMs * 2L)
    private val durations = LongArray(bandUpperMHz.size + 1)
    private var previous: ClockResidencySample? = null
    private var lastTimestamp: Long? = null
    private var covered = 0L
    private var transitions = 0
    private var rise: Long? = null
    private var drop: Long? = null
    private var discontinuity = false
    private var unverified = false

    fun accept(sample: ClockResidencySample) {
        if (sample.elapsedMs < window.startMs || sample.elapsedMs > window.endMs) return
        val last = lastTimestamp
        if (last != null && sample.elapsedMs <= last) {
            discontinuity = true
            previous = null
            return
        }
        lastTimestamp = sample.elapsedMs
        val valid = sample.validity == ReadingValidity.VALID &&
            sample.mhz != null && sample.mhz > 0L
        if (valid && (sample.sourceId.isNullOrBlank() ||
                sample.origin == MeasurementOrigin.UNKNOWN ||
                sample.origin == MeasurementOrigin.USER_DECLARED)) unverified = true
        val old = previous
        if (valid && old != null && old.validity == ReadingValidity.VALID &&
            old.mhz != null && old.mhz > 0L &&
            old.sourceId == sample.sourceId && old.origin == sample.origin &&
            sample.elapsedMs - old.elapsedMs in 1L..maxGapMs) {
            val dt = sample.elapsedMs - old.elapsedMs
            durations[bandUpperMHz.indexOfFirst { old.mhz <= it }
                .let { if (it < 0) durations.lastIndex else it }] += dt
            covered += dt
            // The boolean validity gate does not smart-cast nullable data-class
            // properties. Bind both qualified values before calculating deltas.
            val currentMHz = sample.mhz ?: return
            val previousMHz = old.mhz ?: return
            if (previousMHz != currentMHz) {
                transitions++
                val delta = currentMHz - previousMHz
                if (delta > 0) rise = maxOf(rise ?: 0L, delta)
                else drop = maxOf(drop ?: 0L, -delta)
            }
        } else if (old != null) discontinuity = true
        previous = if (valid) sample else null
    }

    fun result(): ClockResidencyResult {
        val fraction = covered.toDouble() / window.durationMs
        val evidence = when {
            covered == 0L || fraction < 0.80 -> DukoEvidence.INSUFFICIENT
            fraction < 0.95 || discontinuity || unverified -> DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        return ClockResidencyResult(window, bandUpperMHz.toList(), durations.toList(),
            covered, window.durationMs - covered, transitions, rise, drop, evidence)
    }
}
