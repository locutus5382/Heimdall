package com.locutus5382.heimdall.engine.duko

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * M4c stage 1: consume DUKO observations in SQLite insertion order without
 * retaining per-tick DukoObservation objects for every metric.
 *
 * Coverage, source transitions, missing ticks and out-of-order timestamps
 * follow DukoStatistics.analyze exactly. Valid Double values are retained
 * because reporting exact nearest-rank percentiles and an exact median
 * requires order statistics; this is NOT a constant-memory quantile engine.
 *
 * One accumulator is used once per metric/window on the IO analysis path.
 * Call finish() only after all observations have been accepted.
 */
class DukoStatisticsAccumulator(
    private val metric: DukoMetric,
    private val window: DukoWindow,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init { require(expectedCadenceMs in 250L..60_000L) }

    private val maxContinuousGapMs = max(
        expectedCadenceMs + 1_000L, expectedCadenceMs * 2L
    )
    private val values = ArrayList<Double>()
    private var unverifiedSources = false
    private var previous: DukoObservation? = null
    private var precedingTime: Long? = null
    private var lastValidSource: Pair<String?, MeasurementOrigin>? = null
    private var observedRows = 0
    private var missing = 0
    private var reversed = 0
    private var sourceSwitches = 0
    private var coverageMs = 0L
    private var longestGap = 0L
    private var weightedIntegral = 0.0

    fun accept(row: DukoObservation) {
        if (row.metric != metric ||
            row.elapsedMs < window.startMs ||
            row.elapsedMs > window.endMs) return
        observedRows++
        val currentValid = row.isValid()
        if (currentValid && (row.sourceId.isNullOrBlank() ||
                row.origin == MeasurementOrigin.UNKNOWN ||
                row.origin == MeasurementOrigin.USER_DECLARED)) {
            unverifiedSources = true
        }
        val priorTime = precedingTime
        if (priorTime != null && row.elapsedMs <= priorTime) {
            reversed++
            return // Do not normalize or conceal corrupted clock ordering.
        }
        if (priorTime != null) {
            longestGap = max(longestGap, row.elapsedMs - priorTime)
        }
        precedingTime = row.elapsedMs
        if (!currentValid) missing++ else values.add(requireNotNull(row.value))
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
                coverageMs += spanMs
                weightedIntegral += (requireNotNull(old.value) +
                    requireNotNull(row.value)) * 0.5 * spanMs.toDouble()
            }
        }
        previous = row
    }

    fun finish(): DukoMetricAnalysis {
        // Sort the retained scalar values in place; avoid a second O(n)
        // Double list at the finalization peak of a large-session analysis.
        values.sort()
        val n = values.size
        val average = if (n == 0) null else values.average()
        val stdDev = if (n < 2) null else {
            val avg = requireNotNull(average)
            sqrt(values.sumOf { (it - avg) * (it - avg) } / (n - 1).toDouble())
        }
        fun percentile(p: Double): Double? = if (n == 0) null else
            values[(ceil(n * p).toInt() - 1).coerceIn(0, n - 1)]
        val coverage = (coverageMs.toDouble() / window.durationMs.toDouble())
            .coerceIn(0.0, 1.0)
        val grade = when {
            n < 2 || coverage < DukoStatistics.QUALIFIED_COVERAGE ->
                DukoEvidence.INSUFFICIENT
            coverage < DukoStatistics.HIGH_COVERAGE || reversed > 0 ||
                sourceSwitches > 0 || missing > 0 ||
                longestGap > maxContinuousGapMs || unverifiedSources ->
                DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        val warnings = buildList {
            if (n == 0) add("NO_VALID_READINGS")
            if (coverage < DukoStatistics.QUALIFIED_COVERAGE)
                add("LOW_TIME_COVERAGE")
            else if (coverage < DukoStatistics.HIGH_COVERAGE)
                add("PARTIAL_TIME_COVERAGE")
            if (reversed > 0) add("NON_MONOTONIC_TIMESTAMPS")
            if (sourceSwitches > 0) add("SENSOR_SOURCE_CHANGED")
            if (unverifiedSources) add("SOURCE_NOT_VERIFIED")
            if (longestGap > maxContinuousGapMs) add("SAMPLING_GAP")
            if (missing > 0) add("MISSING_OR_INVALID_VALUES")
        }
        return DukoMetricAnalysis(
            metric = metric, window = window, observedRows = observedRows,
            validSamples = n, invalidOrMissingRows = missing,
            outOfOrderRows = reversed, sourceTransitions = sourceSwitches,
            coveredMs = coverageMs, longestGapMs = longestGap,
            coverage = coverage, minimum = values.firstOrNull(),
            maximum = values.lastOrNull(),
            p5 = percentile(0.05), median = if (n == 0) null else
                if (n % 2 == 0) (values[n / 2 - 1] + values[n / 2]) / 2.0
                else values[n / 2],
            p95 = percentile(0.95), p99 = percentile(0.99),
            sampleMean = average,
            timeWeightedMean = if (coverageMs > 0L)
                weightedIntegral / coverageMs.toDouble() else null,
            sampleStandardDeviation = stdDev,
            evidence = grade, warnings = warnings
        )
    }
}
