package com.locutus5382.heimdall.engine

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

enum class DukoEvidenceLevel { SUPPORTED, QUALIFIED, INSUFFICIENT }

data class DukoMetricStatistics(
    val metric: DukoMetric,
    val calculationVersion: String,
    val window: DukoTimeWindow,
    val validSampleCount: Int,
    val rejectedSampleCount: Int,
    val duplicateTimestampCount: Int,
    val reversedTimestampCount: Int,
    val observedGapCount: Int,
    val largestObservedGapMs: Long,
    val coveredMs: Long,
    val coveragePercent: Double,
    val meanOfSamples: Double?,
    val timeWeightedMean: Double?,
    val min: Double?,
    val median: Double?,
    val p95NearestRank: Double?,
    val max: Double?,
    val populationStandardDeviation: Double?,
    val sourceIds: Set<String>,
    val evidence: DukoEvidenceLevel,
) {
    val mixedSources: Boolean get() = sourceIds.size > 1
}

/**
 * DUKO D2: deterministic, gap-aware metric analysis.
 *
 * Each valid sample supports at most one intended sample interval, bounded
 * further by the timestamp of the next observation (even an invalid one).
 * No energy/temperature interpolation occurs across missing samples.
 *
 * P50/P95 are NEAREST-RANK percentiles of valid observations, NOT a frametime
 * metric and not weighted quantiles. The time-weighted mean uses held sample
 * values over only the covered monotonic intervals.
 *
 * Supported/qualified coverage defaults are engineering eligibility hints,
 * not universal statistical significance thresholds.
 */
object DukoStatistics {
    fun summarize(
        metric: DukoMetric,
        observations: List<DukoObservation>,
        window: DukoTimeWindow,
        cadenceMs: Long,
    ): DukoMetricStatistics {
        require(cadenceMs in 100L..3_600_000L)
        val filtered = observations.filter {
            it.metric == metric && it.elapsedMs >= window.startElapsedMs &&
                it.elapsedMs < window.endElapsedMs
        }
        val reversed = filtered.zipWithNext().count { (a, b) ->
            b.elapsedMs < a.elapsedMs
        }
        // Stable sort: first observation wins for a duplicate timestamp.
        val ordered = filtered.sortedBy { it.elapsedMs }
        val unique = ArrayList<DukoObservation>(ordered.size)
        var duplicates = 0
        for (observation in ordered) {
            if (unique.lastOrNull()?.elapsedMs == observation.elapsedMs) duplicates++
            else unique.add(observation)
        }
        val valid = unique.filter { it.usable() }
        val numeric = valid.mapNotNull { it.value }.sorted()
        val sources = valid.mapNotNull { it.sourceId?.takeIf(String::isNotBlank) }
            .toSet()
        val maxGap = unique.zipWithNext().maxOfOrNull { (a, b) ->
            b.elapsedMs - a.elapsedMs
        } ?: 0L
        val gaps = unique.zipWithNext().count { (a, b) ->
            b.elapsedMs - a.elapsedMs > cadenceMs * 3L / 2L
        }

        var coveredMs = 0L
        var weightedSum = 0.0
        unique.forEachIndexed { index, point ->
            if (!point.usable()) return@forEachIndexed
            val nextTime = unique.getOrNull(index + 1)?.elapsedMs
                ?: window.endElapsedMs
            val end = minOf(
                window.endElapsedMs,
                nextTime,
                point.elapsedMs + cadenceMs
            )
            val duration = (end - point.elapsedMs).coerceAtLeast(0L)
            if (duration > 0L) {
                coveredMs += duration
                weightedSum += point.value!! * duration.toDouble()
            }
        }
        val coverage = (100.0 * coveredMs.toDouble() /
            window.durationMs.toDouble()).coerceIn(0.0, 100.0)
        val average = numeric.takeIf { it.isNotEmpty() }?.average()
        val variance = average?.let { mean ->
            numeric.sumOf { (it - mean).pow(2) } / numeric.size
        }
        fun percentile(q: Double): Double? {
            if (numeric.isEmpty()) return null
            val index = (ceil(q * numeric.size).toInt() - 1)
                .coerceIn(0, numeric.lastIndex)
            return numeric[index]
        }
        val quality = when {
            numeric.size < 2 || coverage < 80.0 ->
                DukoEvidenceLevel.INSUFFICIENT
            coverage < 95.0 || reversed > 0 || duplicates > 0 ||
                sources.size > 1 -> DukoEvidenceLevel.QUALIFIED
            else -> DukoEvidenceLevel.SUPPORTED
        }
        return DukoMetricStatistics(
            metric = metric,
            calculationVersion = DukoContract.CALCULATION_VERSION,
            window = window,
            validSampleCount = numeric.size,
            rejectedSampleCount = unique.size - numeric.size,
            duplicateTimestampCount = duplicates,
            reversedTimestampCount = reversed,
            observedGapCount = gaps,
            largestObservedGapMs = maxGap,
            coveredMs = coveredMs,
            coveragePercent = coverage,
            meanOfSamples = average,
            timeWeightedMean =
                if (coveredMs > 0) weightedSum / coveredMs else null,
            min = numeric.firstOrNull(),
            median = percentile(.50),
            p95NearestRank = percentile(.95),
            max = numeric.lastOrNull(),
            populationStandardDeviation = variance?.let(::sqrt),
            sourceIds = sources,
            evidence = quality
        )
    }
}
