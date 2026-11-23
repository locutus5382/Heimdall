package com.locutus5382.heimdall.engine.duko

import kotlin.math.sqrt

data class TimelinePoint(val elapsedMs: Long, val value: Double)
data class TimedSignalSummary(
    val label: String,
    val window: DukoWindow,
    val samples: Int,
    val minimum: Double?,
    val minAtMs: Long?,
    val maximum: Double?,
    val maxAtMs: Long?,
    val arithmeticMean: Double?,
    val sampleStandardDeviation: Double?,
    val trace: List<TimelinePoint>,
    val unit: String,
)

/** Actual readings only; bounded trace, no invented interpolation. */
class TimedSignalAccumulator(
    private val label: String,
    private val unit: String,
    private val window: DukoWindow,
    bucketCount: Int = 150,
) {
    init { require(bucketCount in 8..240) }
    private val sums = DoubleArray(bucketCount)
    private val counts = IntArray(bucketCount)
    private var count = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var minAt: Long? = null
    private var maxAt: Long? = null

    fun accept(elapsedMs: Long, value: Double?) {
        if (elapsedMs !in window.startMs..window.endMs ||
            value == null || !value.isFinite()) return
        count++
        val delta = value - mean
        mean += delta / count
        m2 += delta * (value - mean)
        if (minimum == null || value < minimum!!) {
            minimum = value
            minAt = elapsedMs
        }
        if (maximum == null || value > maximum!!) {
            maximum = value
            maxAt = elapsedMs
        }
        val bucket = (((elapsedMs - window.startMs).toDouble() /
            window.durationMs) * counts.size).toInt().coerceIn(0, counts.lastIndex)
        sums[bucket] += value
        counts[bucket]++
    }

    fun result(): TimedSignalSummary {
        val trace = counts.indices.mapNotNull { i ->
            if (counts[i] == 0) null else TimelinePoint(
                window.startMs + (i.toLong() * 2 + 1) *
                    window.durationMs / (counts.size.toLong() * 2),
                sums[i] / counts[i]
            )
        }
        return TimedSignalSummary(
            label, window, count, minimum, minAt, maximum, maxAt,
            if (count > 0) mean else null,
            if (count >= 2) sqrt((m2 / (count - 1)).coerceAtLeast(0.0))
                else null,
            trace, unit
        )
    }
}
