package com.locutus5382.heimdall.engine.duko

enum class DukoEventType {
    RECORDING_BEGIN, RECORDING_COMPLETE, RECORDING_INTERRUPTED,
    ANALYSIS_WINDOW_BEGIN, ANALYSIS_WINDOW_END,
    CPU_LOAD_HIGH, GPU_LOAD_HIGH, BATTERY_HOT,
    THERMAL_PRESSURE, CPU_PSI_PRESSURE, MEMORY_PSI_PRESSURE,
    POLICY_CAP_OBSERVED, POLICY_CAP_CLEARED,
}

data class DukoTimelineEvent(
    val type: DukoEventType,
    val startMs: Long,
    val endMs: Long,
    val detail: String,
    val heuristic: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class DukoEventTimeline(
    val window: DukoWindow,
    val events: List<DukoTimelineEvent>,
    val droppedEvents: Int,
    val thresholdsExperimental: Boolean = true,
) {
    val eventCount get() = events.size
}

data class DukoEventSample(
    val elapsedMs: Long,
    val cpuLoadPct: Double?,
    val gpuLoadPct: Double?,
    val batteryTemperatureC: Double?,
    val androidThermalStatus: Int?,
    val cpuPsiSomeAvg10: Double?,
    val memoryPsiSomeAvg10: Double?,
)

/**
 * M1.8 D: derive a bounded, reproducible event timeline from recorded rows.
 * Thresholds are diagnostic heuristics, not proof of throttling, FPS drops,
 * application-specific stalls or the cause of a frequency ceiling.
 */
class DukoEventAccumulator(
    private val window: DukoWindow,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    companion object {
        const val MAX_EVENTS = 250
        const val MAX_RECORDED_EVENTS = 500
    }
    init { require(expectedCadenceMs in 250L..60_000L) }
    private val maxGap = maxOf(expectedCadenceMs * 2L, expectedCadenceMs + 1_000L)
    private val completed = mutableListOf<DukoTimelineEvent>()
    private var rejected = 0
    private var lastObserved: Long? = null

    private inner class ThresholdTracker(
        val type: DukoEventType,
        val label: String,
        val threshold: Double,
        val requiredMs: Long,
    ) {
        var first: Long? = null
        var last: Long? = null
        fun close() {
            val start = first
            val end = last
            if (start != null && end != null && end - start >= requiredMs) {
                add(DukoTimelineEvent(type, start, end, label, heuristic = true))
            }
            first = null
            last = null
        }
        fun accept(t: Long, reading: Double?) {
            val yes = reading != null && reading.isFinite() && reading >= threshold
            if (last != null && (t <= last!! || t - last!! > maxGap)) close()
            if (!yes) {
                close()
                return
            }
            if (first == null) first = t
            last = t
        }
    }

    private val cpuHigh = ThresholdTracker(
        DukoEventType.CPU_LOAD_HIGH, "CPU system load ≥95% for ≥120s", 95.0, 120_000L)
    private val gpuHigh = ThresholdTracker(
        DukoEventType.GPU_LOAD_HIGH, "GPU load ≥95% for ≥120s", 95.0, 120_000L)
    private val batteryHot = ThresholdTracker(
        DukoEventType.BATTERY_HOT, "Battery temperature ≥42°C for ≥15s", 42.0, 15_000L)
    private val thermal = ThresholdTracker(
        DukoEventType.THERMAL_PRESSURE, "Android thermal status ≥MODERATE for ≥10s",
        2.0, 10_000L)
    private val cpuPsi = ThresholdTracker(
        DukoEventType.CPU_PSI_PRESSURE, "System CPU PSI some avg10 ≥10% for ≥20s",
        10.0, 20_000L)
    private val memoryPsi = ThresholdTracker(
        DukoEventType.MEMORY_PSI_PRESSURE, "System memory PSI some avg10 ≥10% for ≥20s",
        10.0, 20_000L)

    private fun add(event: DukoTimelineEvent) {
        if (completed.size < MAX_RECORDED_EVENTS) completed += event else rejected++
    }

    fun accept(row: DukoEventSample) {
        val t = row.elapsedMs
        if (t !in window.startMs..window.endMs) return
        val last = lastObserved
        if (last != null && t <= last) {
            listOf(cpuHigh, gpuHigh, batteryHot, thermal, cpuPsi, memoryPsi)
                .forEach { it.close() }
            return // Do not turn a non-monotonic sample into a valid event.
        }
        lastObserved = t
        cpuHigh.accept(t, row.cpuLoadPct?.takeIf { it in 0.0..100.0 })
        gpuHigh.accept(t, row.gpuLoadPct?.takeIf { it in 0.0..100.0 })
        batteryHot.accept(t, row.batteryTemperatureC?.takeIf { it in -30.0..100.0 })
        thermal.accept(t, row.androidThermalStatus?.takeIf { it in 0..6 }?.toDouble())
        cpuPsi.accept(t, row.cpuPsiSomeAvg10?.takeIf { it in 0.0..100.0 })
        memoryPsi.accept(t, row.memoryPsiSomeAvg10?.takeIf { it in 0.0..100.0 })
    }

    fun result(
        caps: FrequencyCapTimeline? = null,
        interrupted: Boolean = false,
        wholeRecording: Boolean = true,
    ): DukoEventTimeline {
        listOf(cpuHigh, gpuHigh, batteryHot, thermal, cpuPsi, memoryPsi)
            .forEach { it.close() }
        val all = ArrayList<DukoTimelineEvent>()
        all += DukoTimelineEvent(
            if (wholeRecording) DukoEventType.RECORDING_BEGIN
            else DukoEventType.ANALYSIS_WINDOW_BEGIN,
            window.startMs, window.startMs,
            if (wholeRecording) "Recorded evidence begins"
            else "Selected analysis window begins"
        )
        all += completed
        caps?.events?.forEach { cap ->
            all += DukoTimelineEvent(
                if (cap.restricted) DukoEventType.POLICY_CAP_OBSERVED
                else DukoEventType.POLICY_CAP_CLEARED,
                cap.elapsedMs, cap.elapsedMs,
                cap.policyId + ": ceiling " + cap.activeMaxMHz + " / " +
                    cap.hardwareMaxMHz + " MHz (cause unknown)"
            )
        }
        all += DukoTimelineEvent(
            if (!wholeRecording) DukoEventType.ANALYSIS_WINDOW_END
            else if (interrupted) DukoEventType.RECORDING_INTERRUPTED
            else DukoEventType.RECORDING_COMPLETE,
            window.endMs, window.endMs,
            if (!wholeRecording) "Selected analysis window ends"
            else if (interrupted) "Session interrupted; evidence may be incomplete"
            else "Recorded evidence ends"
        )
        val ordered = all.sortedWith(compareBy<DukoTimelineEvent> { it.startMs }
            .thenBy { it.type.ordinal })
        return DukoEventTimeline(window, ordered.take(MAX_EVENTS),
            rejected + (ordered.size - MAX_EVENTS).coerceAtLeast(0))
    }
}
