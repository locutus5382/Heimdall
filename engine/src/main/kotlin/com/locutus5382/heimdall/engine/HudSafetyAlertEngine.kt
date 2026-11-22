package com.locutus5382.heimdall.engine

/**
 * Read-only, sample-driven HUD alerts. Durations use recording monotonic time,
 * not wall time or repeated Canvas redraws. Unknown data is never normal data.
 * Thresholds are adjustable policy defaults, NOT universal hardware limits.
 */
enum class HudAlertSeverity { INFO, CAUTION, IMPORTANT, HIGH, CRITICAL }

enum class HudAlertCategory {
    BATTERY_WARM, BATTERY_HOT, BATTERY_PERSISTENT,
    ANDROID_THERMAL, CPU_SUSTAINED_LOAD, GPU_SUSTAINED_LOAD,
    BATTERY_SENSOR_LOST,
}

data class HudSafetyAlert(
    val category: HudAlertCategory,
    val severity: HudAlertSeverity,
    val shortLabel: String,
    val description: String,
    val elapsedMs: Long,
)

data class HudSafetyState(
    val alerts: List<HudSafetyAlert> = emptyList(),
    val batteryIndicator: ThermalIndicatorState = ThermalIndicatorState.NOMINAL,
    val batteryTemperatureUnknown: Boolean = true,
) {
    val primary: HudSafetyAlert? get() = alerts.maxWithOrNull(
        compareBy<HudSafetyAlert> { it.severity.ordinal }
            .thenBy { -it.category.ordinal }
    )
}

/** Time above threshold, with separate lower clear threshold and clear delay. */
internal class HudSustainedThreshold(
    private val enter: Double,
    private val exit: Double,
    private val minimumMs: Long,
    private val recoveryMs: Long,
) {
    init {
        require(enter > exit && minimumMs >= 0 && recoveryMs >= 0)
    }
    private var enteredAt: Long? = null
    private var recoveryAt: Long? = null
    var active: Boolean = false
        private set

    fun reset() {
        enteredAt = null
        recoveryAt = null
        active = false
    }

    fun observe(elapsedMs: Long, value: Double?): Boolean {
        if (value == null || !value.isFinite()) {
            reset()
            return false
        }
        if (active) {
            if (value < exit) {
                val beginning = recoveryAt ?: elapsedMs.also { recoveryAt = it }
                if (elapsedMs - beginning >= recoveryMs) reset()
            } else recoveryAt = null
        } else if (value >= enter) {
            val beginning = enteredAt ?: elapsedMs.also { enteredAt = it }
            if (elapsedMs - beginning >= minimumMs) {
                active = true
                recoveryAt = null
            }
        } else enteredAt = null
        return active
    }
}

class HudSafetyAlertEngine {
    private val warm = HudSustainedThreshold(38.0, 37.0, 0L, 30_000L)
    private val hot = HudSustainedThreshold(42.0, 40.0, 15_000L, 60_000L)
    private val persistent = HudSustainedThreshold(45.0, 43.0, 30_000L, 60_000L)
    private val cpuLoad = HudSustainedThreshold(95.0, 90.0, 120_000L, 10_000L)
    private val gpuLoad = HudSustainedThreshold(95.0, 90.0, 120_000L, 10_000L)
    private var sessionId: Long? = null
    private var lastElapsedMs: Long? = null
    private var previousBatteryConcern = false
    private var batterySensorLost = false
    private var lastState = HudSafetyState()

    fun reset() {
        sessionId = null
        lastElapsedMs = null
        previousBatteryConcern = false
        batterySensorLost = false
        warm.reset()
        hot.reset()
        persistent.reset()
        cpuLoad.reset()
        gpuLoad.reset()
        lastState = HudSafetyState()
    }

    /** Hide cached alerts when no fresh recorder sample arrives. This does not advance
     * hysteresis timers or invent a new observation; the next real sample owns recovery.
     * Caller and DeviceSample.observedElapsedMs must use the same monotonic clock.
     */
    fun visibleStateAt(nowElapsedMs: Long): HudSafetyState {
        val observed = lastElapsedMs ?: return HudSafetyState()
        if (nowElapsedMs < observed || nowElapsedMs - observed > 4_000L) {
            return HudSafetyState(batteryTemperatureUnknown = true)
        }
        return lastState
    }

    fun observe(recordingSessionId: Long, sample: DeviceSample?): HudSafetyState {
        if (sessionId != recordingSessionId) {
            reset()
            sessionId = recordingSessionId
        }
        // Only a real new monotonic sample advances a duration timer.
        val now = sample?.observedElapsedMs ?: return lastState
        val old = lastElapsedMs
        if (old != null && now <= old) return lastState
        // Never bridge an unknown sensor interval or an extended sampler outage.
        if (old != null && now - old > 4_000L) {
            warm.reset()
            hot.reset()
            persistent.reset()
            cpuLoad.reset()
            gpuLoad.reset()
        }
        lastElapsedMs = now

        val batteryTemp = sample.batteryTemperatureC?.takeIf {
            it.isFinite() && it in -30.0..100.0
        }
        val wasConcerned = previousBatteryConcern || batterySensorLost
        val isWarm = warm.observe(now, batteryTemp)
        val isHot = hot.observe(now, batteryTemp)
        val isPersistent = persistent.observe(now, batteryTemp)
        previousBatteryConcern = isWarm || isHot || isPersistent
        batterySensorLost = batteryTemp == null && wasConcerned
        val cpu = sample.cpuLoadPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val gpu = sample.gpuLoadPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val highCpu = cpuLoad.observe(now, cpu)
        val highGpu = gpuLoad.observe(now, gpu)

        val alerts = buildList<HudSafetyAlert> {
            fun addAlert(
                type: HudAlertCategory, severity: HudAlertSeverity,
                short: String, description: String,
            ) { add(HudSafetyAlert(type, severity, short, description, now)) }
            if (isPersistent) addAlert(HudAlertCategory.BATTERY_PERSISTENT,
                HudAlertSeverity.HIGH, "BAT VERY HOT", "Battery temperature elevated for 30s or more")
            else if (isHot) addAlert(HudAlertCategory.BATTERY_HOT,
                HudAlertSeverity.IMPORTANT, "BAT HOT", "Sustained battery temperature at or above 42°C")
            else if (isWarm) addAlert(HudAlertCategory.BATTERY_WARM,
                HudAlertSeverity.CAUTION, "BAT WARM", "Battery temperature at or above 38°C")
            if (batteryTemp == null && wasConcerned) addAlert(
                HudAlertCategory.BATTERY_SENSOR_LOST, HudAlertSeverity.CAUTION,
                "BAT TEMP ?", "Battery temperature signal lost; cooling is not verified")
            // Android PowerManager.THERMAL_STATUS_NONE..SHUTDOWN = 0..6.
            // STATUS_LIGHT is intentionally not a warning. No OEM thermal
            // degrees-C thresholds are inferred from this API.
            when (sample.thermalStatus) {
                2 -> addAlert(HudAlertCategory.ANDROID_THERMAL,
                    HudAlertSeverity.IMPORTANT, "OS THERMAL", "Android reports moderate thermal pressure")
                3 -> addAlert(HudAlertCategory.ANDROID_THERMAL,
                    HudAlertSeverity.HIGH, "OS THERMAL HIGH", "Android reports severe thermal pressure")
                4, 5, 6 -> addAlert(HudAlertCategory.ANDROID_THERMAL,
                    HudAlertSeverity.CRITICAL, "OS THERMAL CRIT",
                    "Android reports critical or higher thermal pressure")
            }
            if (highCpu) addAlert(HudAlertCategory.CPU_SUSTAINED_LOAD,
                HudAlertSeverity.INFO, "CPU HIGH LOAD",
                "CPU at or above 95% for two minutes; not a damage or FPS claim")
            if (highGpu) addAlert(HudAlertCategory.GPU_SUSTAINED_LOAD,
                HudAlertSeverity.INFO, "GPU HIGH LOAD",
                "GPU at or above 95% for two minutes; not a damage or FPS claim")
        }
        val indicator = when {
            isHot || isPersistent -> ThermalIndicatorState.SUSTAINED_HIGH_BATTERY_TEMP
            isWarm -> ThermalIndicatorState.ELEVATED_TEMP
            else -> ThermalIndicatorState.NOMINAL
        }
        return HudSafetyState(alerts, indicator, batteryTemp == null).also {
            lastState = it
        }
    }
}
