package com.locutus5382.heimdall.engine.duko

import kotlin.math.max

/**
 * D3: temperature trend characterization, NOT proof of thermal throttling.
 * Regression is calculated over the longest continuous valid sensor segment;
 * gaps, source changes and invalid readings cannot invent a heating slope.
 */
data class DukoThermalReport(
    val metric: DukoMetric,
    val statistics: DukoMetricAnalysis,
    val continuousSegmentMs: Long,
    val continuousSamples: Int,
    val temperatureChangeC: Double?,
    val warmingSlopeCPerMinute: Double?,
    val evidence: DukoEvidence,
    val warnings: List<String>,
)

object DukoThermal {
    const val MIN_TREND_DURATION_MS = 120_000L
    const val MIN_TREND_SAMPLES = 8

    fun analyze(
        metric: DukoMetric,
        window: DukoWindow,
        readings: List<DukoObservation>,
        expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
    ): DukoThermalReport {
        require(metric in setOf(
            DukoMetric.CPU_TEMPERATURE,
            DukoMetric.GPU_TEMPERATURE,
            DukoMetric.BATTERY_TEMPERATURE
        )) { "THERMAL_SENSOR_REQUIRED" }
        val summary = DukoStatistics.analyze(
            metric, window, readings, expectedCadenceMs
        )
        val maxGap = max(expectedCadenceMs * 2L, expectedCadenceMs + 1_000L)
        val rows = readings.filter {
            it.metric == metric && it.elapsedMs in window.startMs..window.endMs
        }
        var longest = emptyList<DukoObservation>()
        var segment = ArrayList<DukoObservation>()
        fun completeSegment() {
            if (segment.size > 1 &&
                (longest.size < 2 ||
                    segment.last().elapsedMs - segment.first().elapsedMs >
                    longest.last().elapsedMs - longest.first().elapsedMs)) {
                longest = segment.toList()
            }
            segment = ArrayList()
        }
        for (row in rows) {
            if (!row.isValid()) {
                completeSegment()
                continue
            }
            val prev = segment.lastOrNull()
            if (prev != null &&
                (row.elapsedMs <= prev.elapsedMs ||
                    row.elapsedMs - prev.elapsedMs > maxGap ||
                    row.sourceId != prev.sourceId)) {
                completeSegment()
            }
            segment.add(row)
        }
        completeSegment()
        val duration = if (longest.size >= 2)
            longest.last().elapsedMs - longest.first().elapsedMs else 0L
        val canFit = longest.size >= MIN_TREND_SAMPLES &&
            duration >= MIN_TREND_DURATION_MS &&
            summary.coverage >= DukoStatistics.QUALIFIED_COVERAGE
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
            if (duration < MIN_TREND_DURATION_MS ||
                longest.size < MIN_TREND_SAMPLES) add("THERMAL_TREND_TOO_SHORT")
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
