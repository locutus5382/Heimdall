package com.locutus5382.heimdall.engine.duko

import kotlin.math.sqrt

/** x is the centre of actually observed samples in its bucket, not a fake sample. */
data class TimelinePoint(
    val elapsedMs: Long,
    val value: Double,
    /** True only if the previous displayed bucket is adjacent AND gap-free. */
    val continuousFromPrevious: Boolean = false,
)

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

/**
 * Bounded real-data plot buckets. No line should cross an unobserved interval:
 * synthetic bucket centres and consecutive occupied buckets ALONE are not
 * evidence of continuity. Validate adjacent actual reading times using the
 * saved session cadence, including gaps that occur inside a single bucket.
 */
class TimedSignalAccumulator(
    private val label: String,
    private val unit: String,
    private val window: DukoWindow,
    bucketCount: Int = 150,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init {
        require(bucketCount in 8..240)
        require(expectedCadenceMs in 250L..60_000L)
    }
    private val maxGapMs = maxOf(expectedCadenceMs * 2L, expectedCadenceMs + 1_000L)
    private val sums = DoubleArray(bucketCount)
    private val counts = IntArray(bucketCount)
    private val firstTimes = LongArray(bucketCount) { -1L }
    private val lastTimes = LongArray(bucketCount) { -1L }
    private val discontinuous = BooleanArray(bucketCount)
    private var priorTime: Long? = null
    private var priorBucket: Int? = null

    private var count = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var minAt: Long? = null
    private var maxAt: Long? = null

    fun accept(elapsedMs: Long, value: Double?) {
        if (elapsedMs !in window.startMs..window.endMs ||
            value == null || !value.isFinite()) {
            // A missing/null reading is NOT an observed 0; if a later reading
            // returns after a long gap the next accept checks actual dt.
            return
        }
        val bucket = (((elapsedMs - window.startMs).toDouble() /
            window.durationMs) * counts.size).toInt().coerceIn(0, counts.lastIndex)
        val before = priorTime
        if (before != null) {
            if (elapsedMs <= before) {
                priorBucket?.let { discontinuous[it] = true }
                discontinuous[bucket] = true
                priorTime = null
                priorBucket = null
                return // Never draw time-reversed sensor evidence.
            }
            if (elapsedMs - before > maxGapMs) {
                priorBucket?.let { discontinuous[it] = true }
                discontinuous[bucket] = true
            }
        }
        if (firstTimes[bucket] == -1L) firstTimes[bucket] = elapsedMs
        lastTimes[bucket] = elapsedMs
        priorTime = elapsedMs
        priorBucket = bucket
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
        sums[bucket] += value
        counts[bucket]++
    }

    fun result(): TimedSignalSummary {
        var previousIndex: Int? = null
        val trace = counts.indices.mapNotNull { i ->
            if (counts[i] == 0) return@mapNotNull null
            val previous = previousIndex
            val connect = previous != null && previous + 1 == i &&
                !discontinuous[previous] && !discontinuous[i] &&
                firstTimes[i] > lastTimes[previous] &&
                firstTimes[i] - lastTimes[previous] <= maxGapMs
            previousIndex = i
            TimelinePoint(
                elapsedMs = firstTimes[i] + (lastTimes[i] - firstTimes[i]) / 2L,
                value = sums[i] / counts[i],
                continuousFromPrevious = connect,
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
