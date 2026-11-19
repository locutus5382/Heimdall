package com.locutus5382.heimdall.engine

/** Android-independent HUD presentation state; recording remains authoritative. */
enum class HudLayout(val token: String, val title: String) {
    PILL("pill", "Compact Pill"),
    CARD("card", "Glass Card"),
    RIBBON("ribbon", "Telemetry Ribbon");

    fun next(): HudLayout = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(token: String?): HudLayout =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) } ?: PILL
    }
}

enum class HudMetric(val token: String, val title: String) {
    CPU_LOAD("cpu", "CPU load"),
    GPU_CLOCK("gpu", "GPU clock"),
    BATTERY("bat", "Battery"),
    BATTERY_TEMP("temp", "Battery temperature"),
    CPU_CLOCK("clock", "Top CPU clock"),
    FREE_MEMORY("ram", "Free RAM");

    companion object {
        val defaults = setOf(CPU_LOAD, GPU_CLOCK, BATTERY, BATTERY_TEMP)
        fun fromToken(token: String): HudMetric? =
            entries.firstOrNull { it.token == token }
        fun parse(raw: String?): Set<HudMetric> {
            if (raw == null) return defaults
            return raw.split(',').mapNotNull { fromToken(it.trim()) }.toSet()
        }
    }
}

data class HudProfile(
    val layout: HudLayout = HudLayout.PILL,
    val scale: Float = 1f,
    val opacity: Float = 0.76f,
    val locked: Boolean = false,
    val metrics: Set<HudMetric> = HudMetric.defaults,
) {
    fun sanitized(): HudProfile = copy(
        scale = scale.takeIf { it.isFinite() }?.coerceIn(0.75f, 1.30f) ?: 1f,
        opacity = opacity.takeIf { it.isFinite() }?.coerceIn(0.45f, 0.90f) ?: 0.76f,
        // Always leave a meaningful minimum presentation; no fake readings.
        metrics = metrics.ifEmpty { HudMetric.defaults }
    )
}

/** Monotonic bounded ring, reset at session boundaries. No separate sampler. */
class HudTelemetryHistory(private val limit: Int = 36) {
    init { require(limit in 2..240) }

    data class Point(
        val timeMs: Long,
        val cpuLoad: Double?,
        val gpuClock: Long?,
        val batteryTemp: Double?,
    )

    private val points = ArrayDeque<Point>()
    private var sessionId: Long? = null
    private var lastTimeMs: Long = Long.MIN_VALUE

    fun add(id: Long, sample: DeviceSample?) {
        if (id != sessionId) {
            points.clear()
            lastTimeMs = Long.MIN_VALUE
            sessionId = id
        }
        if (sample == null || sample.observedAtMs <= lastTimeMs) return
        lastTimeMs = sample.observedAtMs
        if (points.size == limit) points.removeFirst()
        points.addLast(
            Point(
                sample.observedAtMs,
                sample.cpuLoadPercent?.takeIf { it.isFinite() && it in 0.0..100.0 },
                sample.gpuClockMHz?.takeIf { it > 0 },
                sample.batteryTemperatureC?.takeIf { it.isFinite() && it in -30.0..100.0 }
            )
        )
    }

    fun clear() {
        points.clear()
        sessionId = null
        lastTimeMs = Long.MIN_VALUE
    }

    fun snapshot(): List<Point> = points.toList()
}
