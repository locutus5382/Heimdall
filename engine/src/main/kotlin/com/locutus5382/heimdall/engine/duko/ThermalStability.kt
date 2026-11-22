package com.locutus5382.heimdall.engine.duko

import kotlin.math.abs

/**
 * M9d: bounded early/late thermal stability characterization for one sensor.
 * Deliberately descriptive: no causal throttling, fan or game-performance claims.
 * Only pairs of contiguous, same-source valid readings contribute time.
 */
data class ThermalStabilityResult(
    val metric: DukoMetric,
    val earlyMeanC: Double?,
    val lateMeanC: Double?,
    val lateMinusEarlyC: Double?,
    val lateSlopeCPerMinute: Double?,
    val latePlateauObserved: Boolean,
    val earlyCoveredMs: Long,
    val lateCoveredMs: Long,
    val evidence: DukoEvidence,
    val warnings: List<String>,
)

class ThermalStabilityAccumulator(
    private val metric: DukoMetric,
    private val window: DukoWindow,
    private val phaseMs: Long = 120_000L,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init {
        require(metric in setOf(DukoMetric.BATTERY_TEMPERATURE,
            DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE))
        require(phaseMs >= 120_000L && window.durationMs >= phaseMs * 2L)
        require(expectedCadenceMs in 250L..60_000L)
    }
    private val maxGapMs = maxOf(expectedCadenceMs * 2L, expectedCadenceMs + 1_000L)
    private val earlyEnd = window.startMs + phaseMs
    private val lateStart = window.endMs - phaseMs
    private data class Phase(
        var covered: Long = 0,
        var weighted: Double = 0.0,
        var xSum: Double = 0.0,
        var ySum: Double = 0.0,
        var xxSum: Double = 0.0,
        var xySum: Double = 0.0,
        var intervals: Int = 0,
    ) {
        fun add(start: Long, end: Long, value: Double, originMs: Long) {
            val dt = end - start
            if (dt <= 0L) return
            val x = ((start - originMs).toDouble() + dt / 2.0) / 60_000.0
            val weight = dt.toDouble()
            covered += dt
            weighted += weight * value
            xSum += weight * x
            ySum += weight * value
            xxSum += weight * x * x
            xySum += weight * x * value
            intervals++
        }
        fun mean() = if (covered > 0L) weighted / covered else null
        fun slope(): Double? {
            if (covered == 0L) return null
            val n = covered.toDouble()
            val denominator = xxSum - xSum * xSum / n
            return if (denominator > 1e-9)
                ((xySum - xSum * ySum / n) / denominator).takeIf { it.isFinite() }
            else null
        }
    }
    private val early = Phase()
    private val late = Phase()
    private var previous: DukoObservation? = null
    private var lastTime: Long? = null
    private var source: String? = null
    private var origin: MeasurementOrigin? = null
    private var discontinuity = false
    private var unverified = false

    fun accept(row: DukoObservation) {
        if (row.metric != metric || row.elapsedMs !in window.startMs..window.endMs) return
        val last = lastTime
        if (last != null && row.elapsedMs <= last) {
            discontinuity = true
            previous = null
            return
        }
        lastTime = row.elapsedMs
        if (!row.isValid()) {
            discontinuity = true
            previous = null
            return
        }
        if (row.sourceId.isNullOrBlank() || row.origin == MeasurementOrigin.UNKNOWN ||
            row.origin == MeasurementOrigin.USER_DECLARED) unverified = true
        if (source == null) {
            source = row.sourceId
            origin = row.origin
        } else if (source != row.sourceId || origin != row.origin) {
            discontinuity = true
            previous = null
        }
        val old = previous
        if (old != null && old.sourceId == row.sourceId && old.origin == row.origin &&
            row.elapsedMs - old.elapsedMs in 1L..maxGapMs) {
            val a = old.elapsedMs
            val b = row.elapsedMs
            val value = requireNotNull(old.value)
            early.add(maxOf(a, window.startMs), minOf(b, earlyEnd), value, window.startMs)
            late.add(maxOf(a, lateStart), minOf(b, window.endMs), value, lateStart)
        } else if (old != null) discontinuity = true
        previous = row
    }

    fun result(): ThermalStabilityResult {
        val earlyMean = early.mean()
        val lateMean = late.mean()
        val coverage = minOf(early.covered, late.covered).toDouble() / phaseMs
        val slope = if (late.covered >= phaseMs * 8L / 10L && late.intervals >= 8)
            late.slope() else null
        val adequate = coverage >= 0.80 && early.intervals >= 8 &&
            late.intervals >= 8 && !unverified && !discontinuity
        val grade = when {
            !adequate -> DukoEvidence.INSUFFICIENT
            coverage < 0.95 -> DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        val delta = if (grade != DukoEvidence.INSUFFICIENT &&
            earlyMean != null && lateMean != null) lateMean - earlyMean else null
        return ThermalStabilityResult(metric,
            if (grade == DukoEvidence.INSUFFICIENT) null else earlyMean,
            if (grade == DukoEvidence.INSUFFICIENT) null else lateMean,
            delta,
            if (grade == DukoEvidence.INSUFFICIENT) null else slope,
            grade != DukoEvidence.INSUFFICIENT && slope != null &&
                abs(slope) <= 0.10,
            early.covered, late.covered, grade,
            buildList {
                if (coverage < 0.80) add("THERMAL_PHASE_COVERAGE_INSUFFICIENT")
                if (discontinuity) add("THERMAL_SOURCE_OR_CADENCE_DISCONTINUITY")
                if (unverified) add("THERMAL_SENSOR_PROVENANCE_UNVERIFIED")
                add("THERMAL_PLATEAU_APPROXIMATE_NOT_CAUSAL_THROTTLING_EVIDENCE")
            })
    }
}
