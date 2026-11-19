package com.locutus5382.heimdall.engine

/**
 * DUKO D0: Android-free, versioned measurement vocabulary.
 * Declared configuration is not hardware telemetry. A policy cap is an observed
 * restriction; it cannot prove which app or thermal controller applied it.
 */
object DukoContract {
    const val VERSION = 1
    const val CALCULATION_VERSION = "duko-statistics-1"
}

enum class DukoUnit(val symbol: String) {
    PERCENT("%"), MHZ("MHz"), CELSIUS("°C"), WATT("W"), MILLIVOLT("mV"),
    MICROAMPERE("µA"), MIB("MiB"), SECONDS("s"), HEADROOM("ratio")
}

enum class DukoMetric(val unit: DukoUnit, val title: String) {
    CPU_UTILIZATION(DukoUnit.PERCENT, "CPU utilization"),
    GPU_UTILIZATION(DukoUnit.PERCENT, "GPU utilization"),
    CPU_CLOCK(DukoUnit.MHZ, "CPU clock"),
    GPU_CLOCK(DukoUnit.MHZ, "GPU clock"),
    BATTERY_TEMPERATURE(DukoUnit.CELSIUS, "Battery temperature"),
    CPU_TEMPERATURE(DukoUnit.CELSIUS, "CPU temperature"),
    GPU_TEMPERATURE(DukoUnit.CELSIUS, "GPU temperature"),
    BATTERY_LEVEL(DukoUnit.PERCENT, "Battery level"),
    BATTERY_CURRENT(DukoUnit.MICROAMPERE, "Battery current"),
    BATTERY_VOLTAGE(DukoUnit.MILLIVOLT, "Battery voltage"),
    FREE_MEMORY(DukoUnit.MIB, "Available memory"),
    THERMAL_HEADROOM(DukoUnit.HEADROOM, "Android thermal headroom")
}

enum class DukoSourceKind {
    ANDROID_PUBLIC_API, READ_ONLY_KERNEL, MANUFACTURER_SPEC,
    USER_DECLARED, IMPORTED_WITH_PROVENANCE, UNKNOWN
}
enum class DukoAvailability {
    VALID, UNSUPPORTED, PERMISSION_DENIED, TEMPORARILY_UNREADABLE,
    INVALID_VALUE, STALE
}

data class DukoSource(
    val id: String,
    val kind: DukoSourceKind,
    val unit: DukoUnit,
    /** Optional Linux node/API descriptor. No IDs or secret data. */
    val location: String? = null,
    val availability: DukoAvailability = DukoAvailability.VALID,
    val detail: String? = null,
) {
    init { require(id.isNotBlank()) }
}

data class DukoObservation(
    val metric: DukoMetric,
    /** Android elapsedRealtime() milliseconds, not wall-clock time. */
    val elapsedMs: Long,
    val value: Double?,
    val sourceId: String? = null,
    val availability: DukoAvailability =
        if (value == null) DukoAvailability.UNSUPPORTED else DukoAvailability.VALID,
) {
    /** A numerical zero is valid only when the actual source measured zero. */
    fun usable(): Boolean =
        availability == DukoAvailability.VALID &&
            value != null && value.isFinite() && elapsedMs >= 0
}

data class DukoTimeWindow(val startElapsedMs: Long, val endElapsedMs: Long) {
    init {
        require(startElapsedMs >= 0L)
        require(endElapsedMs > startElapsedMs)
    }
    val durationMs: Long get() = endElapsedMs - startElapsedMs
}

enum class DukoSettingOrigin {
    USER_DECLARED, VERIFIED_DEVICE_SETTING, IMPORTED_USER_METADATA, UNKNOWN
}
data class DukoSetting(
    val value: String,
    val origin: DukoSettingOrigin = DukoSettingOrigin.USER_DECLARED,
) {
    init { require(value.isNotBlank()) }
}

/** No inferred causal relationship from a CPU cap to ClusterTune. */
enum class DukoUnderclockDeclaration { UNKNOWN, NOT_USED, USED }

/**
 * A single session's study tags, editable *without changing the samples*.
 * Missing labels remain null, rather than "stock" or "standard" guesses.
 */
data class DukoSessionContext(
    val sessionId: Long,
    val game: String? = null,
    val emulator: String? = null,
    val emulatorVersion: String? = null,
    val graphicsDriver: String? = null,
    val graphicsDriverVersion: String? = null,
    val performanceMode: DukoSetting? = null,
    val fanProfile: DukoSetting? = null,
    val underclockUsed: DukoUnderclockDeclaration = DukoUnderclockDeclaration.UNKNOWN,
    val tunerName: String? = null,
    val tunerProfile: DukoSetting? = null,
    val testProfile: String? = null,
    val workloadMarker: String? = null,
    val notes: String? = null,
) {
    init { require(sessionId > 0L) }
}

/**
 * Hardware identity and *observed* limits. Silicon, policy and sampled clocks
 * are distinct quantities and their measurements must not be conflated.
 */
data class DukoCpuPolicySpec(
    val id: String,
    val relatedCpuIds: String?,
    val hardwareMaxMHz: Long?,
    val currentPolicyMaxMHz: Long?,
    val currentClockMHz: Long?,
)

data class DukoSensorSpec(
    val metric: DukoMetric,
    val source: DukoSource,
)

data class DukoDeviceProfile(
    val contractVersion: Int = DukoContract.VERSION,
    val manufacturer: String,
    val model: String,
    val soc: String?,
    val androidApi: Int,
    val androidBuild: String?,
    val kernelRelease: String?,
    val totalMemoryMiB: Long?,
    val cpuPolicies: List<DukoCpuPolicySpec>,
    val sensors: List<DukoSensorSpec>,
    val observedOemFanSetting: DukoSetting? = null,
    val observedOemPerformanceSetting: DukoSetting? = null,
) {
    /** Observed restriction is not a diagnosis of throttling or a tuner ID. */
    fun policiesBelowReportedHardwareMax(): List<DukoCpuPolicySpec> =
        cpuPolicies.filter { p ->
            val hardware = p.hardwareMaxMHz
            val ceiling = p.currentPolicyMaxMHz
            hardware != null && ceiling != null && hardware > 0L &&
                ceiling > 0L && ceiling < hardware
        }
}
