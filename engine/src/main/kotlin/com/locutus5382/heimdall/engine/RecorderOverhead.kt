package com.locutus5382.heimdall.engine

/**
 * M2.2 / K: bounded, best-effort self-observation. Sampling is unchanged.
 *
 * All values are measured elapsed time, not game FPS, not game CPU usage,
 * and not proof of recorder-only CPU consumption. No events are persisted,
 * uploaded, logged per tick, or rendered in the gameplay HUD.
 */
interface RecorderOverheadSink {
    fun begin(sessionId: Long, intervalMs: Long) {}
    fun tick(
        sessionId: Long,
        readMs: Long,
        appendMs: Long,
        totalMs: Long,
        deadlineLatenessMs: Long,
        missedDeadlines: Long,
        processCpuDeltaMs: Long? = null,
    ) {}
    fun failedTick(sessionId: Long, duringAppend: Boolean) {}
    fun end(sessionId: Long) {}
}

object NoopRecorderOverheadSink : RecorderOverheadSink

/**
 * Bucketed distribution uses constant space and < 32 integer counters,
 * including for overnight sessions. Percentiles are upper-bin bounds
 * (not exact sample percentiles), which is explicitly reflected in the UI.
 */
data class RecorderOverheadSnapshot(
    val sessionId: Long,
    val intervalMs: Long,
    val ticks: Long,
    val failedReads: Long,
    val failedAppends: Long,
    val lateTicks: Long,
    val missedDeadlines: Long,
    val readP50UpperMs: Long?,
    val readP95UpperMs: Long?,
    val appendP50UpperMs: Long?,
    val appendP95UpperMs: Long?,
    val totalP50UpperMs: Long?,
    val totalP95UpperMs: Long?,
    val maxReadMs: Long,
    val maxAppendMs: Long,
    val maxTotalMs: Long,
    val maxDeadlineLatenessMs: Long,
    val processCpuWindowMs: Long?,
    val completed: Boolean,
)

class RecorderOverheadMonitor : RecorderOverheadSink {
    private class Histogram {
        // Cap is only for quantile approximation; real maxima remain exact.
        private val edges = longArrayOf(
            0L, 1L, 2L, 3L, 5L, 8L, 12L, 20L, 30L, 50L,
            75L, 100L, 150L, 200L, 300L, 500L, 750L, 1_000L,
            1_500L, 2_000L, 3_000L, 5_000L, 10_000L, 30_000L,
            Long.MAX_VALUE
        )
        private val counts = LongArray(edges.size)
        private var total = 0L

        fun add(value: Long) {
            val safe = value.coerceAtLeast(0L)
            val index = edges.indexOfFirst { safe <= it }
            counts[index.coerceAtLeast(0)]++
            total++
        }

        fun percentileUpper(percent: Double): Long? {
            if (total == 0L) return null
            val desired = kotlin.math.ceil(total.toDouble() * percent).toLong()
                .coerceIn(1L, total)
            var running = 0L
            for (i in counts.indices) {
                running += counts[i]
                if (running >= desired) return edges[i]
            }
            return edges.last()
        }
    }

    private data class Session(
        val id: Long,
        val interval: Long,
        val read: Histogram = Histogram(),
        val append: Histogram = Histogram(),
        val total: Histogram = Histogram(),
        var successes: Long = 0L,
        var readFailures: Long = 0L,
        var appendFailures: Long = 0L,
        var late: Long = 0L,
        var missed: Long = 0L,
        var maxRead: Long = 0L,
        var maxAppend: Long = 0L,
        var maxTotal: Long = 0L,
        var maxLate: Long = 0L,
        var cpuDeltaSumMs: Long = 0L,
        var cpuObservations: Long = 0L,
        var completed: Boolean = false,
    ) {
        fun snapshot(): RecorderOverheadSnapshot = RecorderOverheadSnapshot(
            sessionId = id,
            intervalMs = interval,
            ticks = successes,
            failedReads = readFailures,
            failedAppends = appendFailures,
            lateTicks = late,
            missedDeadlines = missed,
            readP50UpperMs = read.percentileUpper(.5),
            readP95UpperMs = read.percentileUpper(.95),
            appendP50UpperMs = append.percentileUpper(.5),
            appendP95UpperMs = append.percentileUpper(.95),
            totalP50UpperMs = total.percentileUpper(.5),
            totalP95UpperMs = total.percentileUpper(.95),
            maxReadMs = maxRead,
            maxAppendMs = maxAppend,
            maxTotalMs = maxTotal,
            maxDeadlineLatenessMs = maxLate,
            processCpuWindowMs = if (cpuObservations > 0L) cpuDeltaSumMs else null,
            completed = completed,
        )
    }

    private var active: Session? = null
    private var last: RecorderOverheadSnapshot? = null

    @Synchronized override fun begin(sessionId: Long, intervalMs: Long) {
        require(sessionId > 0L && intervalMs >= 1_000L)
        active?.let { last = it.snapshot() }
        active = Session(sessionId, intervalMs)
    }

    @Synchronized override fun tick(
        sessionId: Long, readMs: Long, appendMs: Long, totalMs: Long,
        deadlineLatenessMs: Long, missedDeadlines: Long, processCpuDeltaMs: Long?,
    ) {
        val state = active?.takeIf { it.id == sessionId && !it.completed } ?: return
        if (readMs < 0L || appendMs < 0L || totalMs < 0L ||
            deadlineLatenessMs < 0L || missedDeadlines < 0L) return
        state.successes++
        state.read.add(readMs)
        state.append.add(appendMs)
        state.total.add(totalMs)
        if (deadlineLatenessMs > 0L) state.late++
        state.missed += missedDeadlines
        state.maxRead = maxOf(state.maxRead, readMs)
        state.maxAppend = maxOf(state.maxAppend, appendMs)
        state.maxTotal = maxOf(state.maxTotal, totalMs)
        state.maxLate = maxOf(state.maxLate, deadlineLatenessMs)
        if (processCpuDeltaMs != null && processCpuDeltaMs >= 0L) {
            // Includes unrelated threads in the same process during this
            // sampling window: an upper bound, not isolated app overhead.
            state.cpuDeltaSumMs += processCpuDeltaMs
            state.cpuObservations++
        }
    }

    @Synchronized override fun failedTick(sessionId: Long, duringAppend: Boolean) {
        val state = active?.takeIf { it.id == sessionId && !it.completed } ?: return
        if (duringAppend) state.appendFailures++ else state.readFailures++
    }

    @Synchronized override fun end(sessionId: Long) {
        val state = active?.takeIf { it.id == sessionId } ?: return
        state.completed = true
        last = state.snapshot()
        active = null
    }

    /** Copy on demand; never polled by HUD/UI every sampling tick. */
    @Synchronized fun snapshot(): RecorderOverheadSnapshot? =
        active?.snapshot() ?: last

    @Synchronized fun clear() {
        active = null
        last = null
    }
}
