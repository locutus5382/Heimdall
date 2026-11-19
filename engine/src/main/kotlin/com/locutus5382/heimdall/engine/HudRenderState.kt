package com.locutus5382.heimdall.engine

import java.util.Locale

/** Explicit states: never mistake load or a high clock for causal throttling. */
enum class ThermalIndicatorState {
    NOMINAL, ELEVATED_TEMP, SUSTAINED_HIGH_BATTERY_TEMP, POLICY_CAP_OBSERVED
}

data class HudReadout(
    val metric: HudMetric,
    val label: String,
    val value: String,
    /** Only metrics with a defensible 0..1 denominator have a micro-gauge. */
    val normalized: Float?,
    val caution: Boolean = false,
    val alert: Boolean = false,
)

data class HudRenderState(
    val sessionId: Long,
    val elapsedLabel: String,
    val recordingLabel: String,
    val readouts: List<HudReadout>,
    val cpuHistory: List<HudTelemetryHistory.Point>,
    val thermal: ThermalIndicatorState,
    val saving: Boolean = false,
) {
    companion object {
        fun recording(
            sessionId: Long,
            elapsedMillis: Long,
            sample: DeviceSample?,
            profile: HudProfile,
            history: List<HudTelemetryHistory.Point>,
            thermal: ThermalIndicatorState = ThermalIndicatorState.NOMINAL,
            saving: Boolean = false,
        ): HudRenderState {
            val seconds = (elapsedMillis.coerceAtLeast(0L) / 1000L)
            val time = if (seconds >= 3600L)
                String.format(Locale.US, "%d:%02d:%02d",
                    seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
            else String.format(Locale.US, "%02d:%02d",
                seconds / 60L, seconds % 60L)

            fun pct(v: Double?) = v?.takeIf {
                it.isFinite() && it in 0.0..100.0
            }?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"

            fun temperature(v: Double?) = v?.takeIf {
                it.isFinite() && it in -30.0..100.0
            }?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—"

            val p = profile.sanitized()
            val fields = HudMetric.entries.filter {
                it in p.metrics && it in p.layout.supportedMetrics()
            }.take(p.layout.metricLimit()).map { key ->
                when (key) {
                    HudMetric.CPU_LOAD -> HudReadout(key, "LOAD",
                        pct(sample?.cpuLoadPercent),
                        sample?.cpuLoadPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
                            ?.div(100.0)?.toFloat())
                    HudMetric.GPU_CLOCK -> HudReadout(key, "GPU CLK",
                        sample?.gpuClockMHz?.takeIf { it > 0L }
                            ?.let { "$it MHz" } ?: "—", null)
                    HudMetric.BATTERY -> HudReadout(key, "BAT",
                        sample?.batteryLevelPercent?.takeIf { it in 0..100 }
                            ?.let { "$it%" } ?: "—",
                        sample?.batteryLevelPercent?.takeIf { it in 0..100 }
                            ?.div(100f))
                    HudMetric.BATTERY_TEMP -> HudReadout(key, "BAT TEMP",
                        temperature(sample?.batteryTemperatureC), null,
                        thermal == ThermalIndicatorState.ELEVATED_TEMP,
                        thermal == ThermalIndicatorState.SUSTAINED_HIGH_BATTERY_TEMP)
                    HudMetric.CPU_CLOCK -> HudReadout(key, "CPU CLK",
                        sample?.cpuClockMHz?.takeIf { it > 0L }
                            ?.let { "$it MHz" } ?: "—", null)
                    HudMetric.FREE_MEMORY -> HudReadout(key, "RAM FREE",
                        sample?.availableMemoryMiB?.takeIf { it >= 0L }
                            ?.let { "$it MiB" } ?: "—", null)
                }
            }
            return HudRenderState(sessionId, time,
                if (saving) "SAVING" else "● REC $time", fields,
                history.takeLast(60), thermal, saving)
        }
    }
}
