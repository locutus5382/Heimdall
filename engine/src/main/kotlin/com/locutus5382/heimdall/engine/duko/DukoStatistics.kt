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
    val minimumAtElapsedMs: Long? = null,
    val maximumAtElapsedMs: Long? = null,
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
        val unverifiedSources = input.any {
            it.isValid() && (it.sourceId.isNullOrBlank() ||
                it.origin == MeasurementOrigin.UNKNOWN ||
                it.origin == MeasurementOrigin.USER_DECLARED)
        }
        var previous: DukoObservation? = null
        var precedingTime: Long? = null
        var missing = 0
        var reversed = 0
        var sourceSwitches = 0
        // Track the last valid sensor even across null/invalid ticks. Such
        // ticks break integration, but cannot erase a real provenance change.
        var lastValidSource: Pair<String?, MeasurementOrigin>? = null
        var coverageMs = 0L
        var longestGap = 0L
        var weightedIntegral = 0.0
        var minValue: Double? = null
        var maxValue: Double? = null
        var minAt: Long? = null
        var maxAt: Long? = null
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
            if (!currentValid) missing++ else {
                val v = requireNotNull(row.value)
                values += v
                if (minValue == null || v < minValue!!) { minValue = v; minAt = row.elapsedMs }
                if (maxValue == null || v > maxValue!!) { maxValue = v; maxAt = row.elapsedMs }
            }
            if (currentValid) {
                val identity = row.sourceId?.trim()
                    ?.takeIf { it.isNotEmpty() } to row.origin
                if (lastValidSource != null && lastValidSource != identity)
                    sourceSwitches++
                lastValidSource = identity
            }
            val old = previous
            if (old != null && old.isValid() && currentValid) {
                val spanMs = row.elapsedMs - old.elapsedMs
                val sameIdentity = old.sourceId?.trim()
                    ?.takeIf { it.isNotEmpty() } ==
                    row.sourceId?.trim()?.takeIf { it.isNotEmpty() } &&
                    old.origin == row.origin
                if (sameIdentity && spanMs in 1L..maxContinuousGapMs) {
                    // Neither a sensor nor a provenance change may create
                    // a fictitious continuous interval of measurements.
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
            coverage < HIGH_COVERAGE || reversed > 0 || sourceSwitches > 0 ||
                missing > 0 || longestGap > maxContinuousGapMs ||
                unverifiedSources ->
                DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        val warnings = buildList {
            if (n == 0) add("NO_VALID_READINGS")
            if (coverage < QUALIFIED_COVERAGE) add("LOW_TIME_COVERAGE")
            else if (coverage < HIGH_COVERAGE) add("PARTIAL_TIME_COVERAGE")
            if (reversed > 0) add("NON_MONOTONIC_TIMESTAMPS")
            if (sourceSwitches > 0) add("SENSOR_SOURCE_CHANGED")
            if (unverifiedSources) add("SOURCE_NOT_VERIFIED")
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
            evidence = grade, warnings = warnings,
            minimumAtElapsedMs = minAt,
            maximumAtElapsedMs = maxAt,
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
