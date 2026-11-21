package com.locutus5382.heimdall.engine.duko

import kotlin.math.max

/**
 * M4c stage 3: stream thermal readings and retain only the current and
 * longest valid continuous segments, not the complete session history.
 * The longest segment remains O(n) in a fully continuous recording because
 * exact reference-compatible two-pass regression is deliberately preserved.
 */
class DukoThermalAccumulator(
    private val metric: DukoMetric,
    private val window: DukoWindow,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init {
        require(metric in setOf(
            DukoMetric.CPU_TEMPERATURE,
            DukoMetric.GPU_TEMPERATURE,
            DukoMetric.BATTERY_TEMPERATURE
        )) { "THERMAL_SENSOR_REQUIRED" }
    }

    private val statistics = DukoStatisticsAccumulator(metric, window, expectedCadenceMs)
    private val maxGap = max(expectedCadenceMs * 2L, expectedCadenceMs + 1_000L)
    private var longest = emptyList<DukoObservation>()
    private var segment = ArrayList<DukoObservation>()
    private var finished = false

    private fun completeSegment() {
        if (segment.size > 1 &&
            (longest.size < 2 ||
                segment.last().elapsedMs - segment.first().elapsedMs >
                longest.last().elapsedMs - longest.first().elapsedMs)) {
            // Transfer ownership rather than duplicating the longest segment.
            longest = segment
        }
        segment = ArrayList()
    }

    fun accept(row: DukoObservation) {
        check(!finished) { "THERMAL_ACCUMULATOR_FINISHED" }
        statistics.accept(row)
        if (row.metric != metric ||
            row.elapsedMs < window.startMs || row.elapsedMs > window.endMs)
            return
        if (!row.isValid()) {
            completeSegment()
            return
        }
        val prev = segment.lastOrNull()
        if (prev != null &&
            (row.elapsedMs <= prev.elapsedMs ||
                row.elapsedMs - prev.elapsedMs > maxGap ||
                row.sourceId != prev.sourceId ||
                row.origin != prev.origin)) {
            completeSegment()
        }
        segment.add(row)
    }

    fun finish(): DukoThermalReport {
        check(!finished) { "THERMAL_ACCUMULATOR_FINISHED" }
        finished = true
        completeSegment()
        val summary = statistics.finish()
        val duration = if (longest.size >= 2)
            longest.last().elapsedMs - longest.first().elapsedMs else 0L
        val canFit = longest.size >= DukoThermal.MIN_TREND_SAMPLES &&
            duration >= DukoThermal.MIN_TREND_DURATION_MS &&
            summary.coverage >= DukoStatistics.QUALIFIED_COVERAGE
        // Match the original DukoThermal two-pass regression arithmetic.
        val slope = if (canFit) {
            val origin = longest.first().elapsedMs
            val xMean = longest.sumOf {
                (it.elapsedMs - origin) / 60_000.0
            } / longest.size
            val yMean = longest.sumOf { requireNotNull(it.value) } /
                longest.size
            var variance = 0.0
            var covariance = 0.0
            longest.forEach {
                val x = (it.elapsedMs - origin) / 60_000.0 - xMean
                val y = requireNotNull(it.value) - yMean
                variance += x * x
                covariance += x * y
            }
            (covariance / variance).takeIf { it.isFinite() }
        } else null
        val grade = when {
            slope == null -> DukoEvidence.INSUFFICIENT
            summary.evidence != DukoEvidence.SUPPORTED ||
                duration.toDouble() / window.durationMs.toDouble() < 0.95 ->
                DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        val warnings = buildList {
            addAll(summary.warnings)
            if (duration < DukoThermal.MIN_TREND_DURATION_MS ||
                longest.size < DukoThermal.MIN_TREND_SAMPLES)
                add("THERMAL_TREND_TOO_SHORT")
            if (duration < window.durationMs * 0.95)
                add("THERMAL_TREND_RESTRICTED_TO_CONTINUOUS_SEGMENT")
            add("TEMPERATURE_TREND_NOT_CAUSAL_THROTTLING_EVIDENCE")
        }
        return DukoThermalReport(
            metric = metric,
            statistics = summary,
            continuousSegmentMs = duration,
            continuousSamples = longest.size,
            temperatureChangeC = if (longest.size >= 2)
                requireNotNull(longest.last().value) -
                    requireNotNull(longest.first().value)
                else null,
            warmingSlopeCPerMinute = slope,
            evidence = grade,
            warnings = warnings
        )
    }
}
