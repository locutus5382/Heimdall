package com.locutus5382.heimdall.engine.duko

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * D2: deterministic measurement statistics. Never reorders timestamps silently,
 * spans missing data, bridges source changes or converts unknown values to zero.
 *
 * Percentiles are nearest-rank percentiles of observed samples. Time-weighted
 * mean uses trapezoidal interpolation only for adjacent VALID, contiguous
 * observations of the same source. It is deliberately a different statistic.
 */
data class DukoMetricAnalysis(
    val metric: DukoMetric,
    val window: DukoWindow,
    val observedRows: Int,
    val validSamples: Int,
    val invalidOrMissingRows: Int,
    val outOfOrderRows: Int,
    val sourceTransitions: Int,
    val coveredMs: Long,
    val longestGapMs: Long,
    val coverage: Double,
    val minimum: Double?,
    val maximum: Double?,
    val p5: Double?,
    val median: Double?,
    val p95: Double?,
    val p99: Double?,
    val sampleMean: Double?,
    val timeWeightedMean: Double?,
    val sampleStandardDeviation: Double?,
    val evidence: DukoEvidence,
    val warnings: List<String>,
)

object DukoStatistics {
    const val HIGH_COVERAGE = 0.95
    const val QUALIFIED_COVERAGE = 0.80

    /** A two-second recorder cadence allows a maximum four-second interval. */
    fun analyze(
        metric: DukoMetric,
        window: DukoWindow,
        samples: List<DukoObservation>,
        expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
    ): DukoMetricAnalysis {
        require(expectedCadenceMs in 250L..60_000L)
        val maxContinuousGapMs = max(expectedCadenceMs + 1_000L,
            expectedCadenceMs * 2L)
        val input = samples.filter {
            it.metric == metric && it.elapsedMs >= window.startMs &&
                it.elapsedMs <= window.endMs
        }
        val values = ArrayList<Double>(input.size)
        var previous: DukoObservation? = null
        var precedingTime: Long? = null
        var missing = 0
        var reversed = 0
        var sourceSwitches = 0
        var coverageMs = 0L
        var longestGap = 0L
        var weightedIntegral = 0.0
        for (row in input) {
            if (precedingTime != null && row.elapsedMs <= precedingTime) {
                reversed++
                continue // Preserve chronological evidence; no sorting.
            }
            if (precedingTime != null) {
                longestGap = max(longestGap, row.elapsedMs - precedingTime)
            }
            precedingTime = row.elapsedMs
            val currentValid = row.isValid()
            if (!currentValid) missing++ else values += requireNotNull(row.value)
            val old = previous
            if (old != null && old.isValid() && currentValid) {
                val spanMs = row.elapsedMs - old.elapsedMs
                if (old.sourceId != null && row.sourceId != null &&
                    old.sourceId != row.sourceId) {
                    sourceSwitches++
                } else if (spanMs in 1L..maxContinuousGapMs) {
                    // No bridging of missing samples or long gaps.
                    coverageMs += spanMs
                    weightedIntegral += (requireNotNull(old.value) +
                        requireNotNull(row.value)) * 0.5 * spanMs.toDouble()
                }
            }
            previous = row
        }
        val sorted = values.sorted()
        val n = sorted.size
        val average = if (n == 0) null else sorted.average()
        val stdDev = if (n < 2) null else {
            val avg = requireNotNull(average)
            sqrt(sorted.sumOf { (it - avg) * (it - avg) } / (n - 1).toDouble())
        }
        fun percentile(p: Double): Double? = if (n == 0) null else
            sorted[(ceil(n * p).toInt() - 1).coerceIn(0, n - 1)]
        val coverage = (coverageMs.toDouble() / window.durationMs.toDouble())
            .coerceIn(0.0, 1.0)
        val grade = when {
            n < 2 || coverage < QUALIFIED_COVERAGE -> DukoEvidence.INSUFFICIENT
            coverage < HIGH_COVERAGE || reversed > 0 || sourceSwitches > 0 ->
                DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        val warnings = buildList {
            if (n == 0) add("NO_VALID_READINGS")
            if (coverage < QUALIFIED_COVERAGE) add("LOW_TIME_COVERAGE")
            else if (coverage < HIGH_COVERAGE) add("PARTIAL_TIME_COVERAGE")
            if (reversed > 0) add("NON_MONOTONIC_TIMESTAMPS")
            if (sourceSwitches > 0) add("SENSOR_SOURCE_CHANGED")
            if (longestGap > maxContinuousGapMs) add("SAMPLING_GAP")
            if (missing > 0) add("MISSING_OR_INVALID_VALUES")
        }
        return DukoMetricAnalysis(
            metric = metric, window = window, observedRows = input.size,
            validSamples = n, invalidOrMissingRows = missing,
            outOfOrderRows = reversed, sourceTransitions = sourceSwitches,
            coveredMs = coverageMs, longestGapMs = longestGap,
            coverage = coverage, minimum = sorted.firstOrNull(),
            maximum = sorted.lastOrNull(),
            p5 = percentile(0.05), median = if (n == 0) null else
                if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
                else sorted[n / 2],
            p95 = percentile(0.95), p99 = percentile(0.99),
            sampleMean = average,
            timeWeightedMean = if (coverageMs > 0L)
                weightedIntegral / coverageMs.toDouble() else null,
            sampleStandardDeviation = stdDev,
            evidence = grade, warnings = warnings
        )
    }
}

/** Transparent, per-metric evidence rather than a misleading global score. */
data class DukoSessionIntegrity(
    val outcome: DukoSessionOutcome,
    val metrics: Map<DukoMetric, DukoMetricAnalysis>,
    val interrupted: Boolean = outcome != DukoSessionOutcome.COMPLETED,
) {
    val adequatelyMeasured: Set<DukoMetric>
        get() = metrics.filterValues {
            it.evidence != DukoEvidence.INSUFFICIENT
        }.keys
    val incomplete: Set<DukoMetric>
        get() = metrics.filterValues {
            it.evidence == DukoEvidence.INSUFFICIENT
        }.keys
}
