package com.locutus5382.heimdall.engine.duko

/**
 * M1.8 B: exact observed statistics for successive 60-second monotonic windows.
 * Intervals are aligned to the selected analysis window's start and use a
 * half-open [start, end) convention except the final terminal reading.
 * Missing minutes remain explicitly empty; no samples are invented.
 */
data class DukoMinuteBlock(
    val ordinal: Int,
    val window: DukoWindow,
    val partial: Boolean,
    val recordedRows: Int,
    val metrics: Map<DukoMetric, DukoMetricAnalysis>,
    val energy: DukoEnergyReport,
    val pressure: Map<String, TimedSignalSummary>,
)

data class DukoMinuteReport(
    val window: DukoWindow,
    val blocks: List<DukoMinuteBlock>,
    val totalBlocks: Long,
    val truncated: Boolean,
) {
    val reportedBlocks: Int get() = blocks.size
}

class DukoMinuteAccumulator(
    private val window: DukoWindow,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    companion object {
        const val MINUTE_MS = 60_000L
        // Fixed cap: do not allocate hours of analysis objects for week-long runs.
        const val MAX_DISPLAY_BLOCKS = 360
        val METRICS = listOf(
            DukoMetric.CPU_LOAD,
            DukoMetric.GPU_LOAD,
            DukoMetric.CPU_CLOCK,
            DukoMetric.GPU_CLOCK,
            DukoMetric.BATTERY_TEMPERATURE,
            DukoMetric.CPU_TEMPERATURE,
            DukoMetric.GPU_TEMPERATURE,
            DukoMetric.AVAILABLE_RAM,
        )
    }

    init { require(expectedCadenceMs in 250L..60_000L) }

    private val cadence = expectedCadenceMs
    private val total = (window.durationMs - 1L) / MINUTE_MS + 1L
    private val active = mutableMapOf<Int, Block>()

    private inner class Block(val index: Int) {
        val start = window.startMs + index.toLong() * MINUTE_MS
        val end = minOf(window.endMs, start + MINUTE_MS)
        val span = DukoWindow(start, end)
        val statistics = METRICS.associateWith {
            DukoStatisticsAccumulator(it, span, cadence)
        }
        val energy = DukoEnergyAccumulator(span, cadence)
        val cpuPsi = TimedSignalAccumulator("CPU PSI SOME AVG10", "%", span, 30)
        val memoryPsi = TimedSignalAccumulator("MEMORY PSI SOME AVG10", "%", span, 30)
        val memoryFullPsi = TimedSignalAccumulator("MEMORY PSI FULL AVG10", "%", span, 30)
        var rows = 0
        fun report() = DukoMinuteBlock(
            ordinal = index + 1,
            window = span,
            partial = span.durationMs < MINUTE_MS,
            recordedRows = rows,
            metrics = statistics.mapValues { it.value.finish() },
            energy = energy.finish(),
            pressure = linkedMapOf(
                "CPU PSI SOME AVG10" to cpuPsi.result(),
                "MEMORY PSI SOME AVG10" to memoryPsi.result(),
                "MEMORY PSI FULL AVG10" to memoryFullPsi.result(),
            ),
        )
    }

    private fun blockAt(t: Long): Block? {
        if (t < window.startMs || t > window.endMs) return null
        val idx = minOf(total - 1, (t - window.startMs) / MINUTE_MS)
        if (idx >= MAX_DISPLAY_BLOCKS) return null
        return active.getOrPut(idx.toInt()) { Block(idx.toInt()) }
    }

    fun acceptTick(elapsedMs: Long) {
        val block = blockAt(elapsedMs) ?: return
        block.rows++
    }

    fun acceptObservation(row: DukoObservation) {
        blockAt(row.elapsedMs)?.statistics?.get(row.metric)?.accept(row)
    }

    fun acceptBattery(point: DukoBatteryPoint) {
        blockAt(point.elapsedMs)?.energy?.accept(point)
    }

    fun acceptPressure(
        elapsedMs: Long,
        cpuSomeAvg10: Double?,
        memorySomeAvg10: Double?,
        memoryFullAvg10: Double?,
    ) {
        val block = blockAt(elapsedMs) ?: return
        block.cpuPsi.accept(elapsedMs, cpuSomeAvg10)
        block.memoryPsi.accept(elapsedMs, memorySomeAvg10)
        block.memoryFullPsi.accept(elapsedMs, memoryFullAvg10)
    }

    fun result(): DukoMinuteReport {
        val count = minOf(total, MAX_DISPLAY_BLOCKS.toLong()).toInt()
        val list = (0 until count).map { idx ->
            // Empty minutes are intentionally visible with INSUFFICIENT evidence.
            (active[idx] ?: Block(idx)).report()
        }
        return DukoMinuteReport(window, list, total, total > MAX_DISPLAY_BLOCKS)
    }
}
