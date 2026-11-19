package com.locutus5382.heimdall.engine.duko

/**
 * DUKO measurement contract v1. Android-free: UI, storage, telemetry and
 * reporting must all reference these definitions. Never synthesize unavailable
 * readings or infer a ClusterTune profile from CPU frequency alone.
 */
object DukoContract {
    const val VERSION = 1
    const val DEFAULT_INTERVAL_MS = 2_000L
}

enum class MeasurementOrigin {
    ANDROID_API, VALIDATED_SYSFS, USER_DECLARED, IMPORTED, DERIVED, UNKNOWN
}

enum class ReadingValidity {
    VALID, UNAVAILABLE, PERMISSION_DENIED, READ_ERROR, INVALID_READING, STALE
}

enum class MeasurementUnit(val symbol: String) {
    PERCENT("%"), CELSIUS("°C"), MEGAHERTZ("MHz"), MILLIAMPERE("mA"),
    MILLIVOLT("mV"), WATT("W"), MEBIBYTE("MiB"), NONE("")
}

enum class DukoMetric(
    val unit: MeasurementUnit,
    val lower: Double?,
    val upper: Double?,
) {
    CPU_LOAD(MeasurementUnit.PERCENT, 0.0, 100.0),
    GPU_LOAD(MeasurementUnit.PERCENT, 0.0, 100.0),
    CPU_CLOCK(MeasurementUnit.MEGAHERTZ, 0.0, null),
    GPU_CLOCK(MeasurementUnit.MEGAHERTZ, 0.0, null),
    CPU_TEMPERATURE(MeasurementUnit.CELSIUS, -30.0, 125.0),
    GPU_TEMPERATURE(MeasurementUnit.CELSIUS, -30.0, 125.0),
    BATTERY_TEMPERATURE(MeasurementUnit.CELSIUS, -30.0, 100.0),
    BATTERY_LEVEL(MeasurementUnit.PERCENT, 0.0, 100.0),
    AVAILABLE_RAM(MeasurementUnit.MEBIBYTE, 0.0, null),
    BATTERY_CURRENT(MeasurementUnit.MILLIAMPERE, null, null),
    BATTERY_VOLTAGE(MeasurementUnit.MILLIVOLT, 0.0, null),
    BATTERY_DISCHARGE_POWER(MeasurementUnit.WATT, 0.0, null);

    fun accepts(value: Double): Boolean =
        value.isFinite() && (lower == null || value >= lower) &&
            (upper == null || value <= upper)
}

/** Immutable, source-aware observation. elapsedMs is a monotonic timestamp. */
data class DukoObservation(
    val elapsedMs: Long,
    val metric: DukoMetric,
    val value: Double?,
    val validity: ReadingValidity = if (value == null)
        ReadingValidity.UNAVAILABLE else ReadingValidity.VALID,
    val sourceId: String? = null,
    val origin: MeasurementOrigin = MeasurementOrigin.UNKNOWN,
) {
    fun isValid(): Boolean =
        validity == ReadingValidity.VALID &&
            value != null && metric.accepts(value)
}

/** Selected interval is half-open: [startMs, endMs). */
data class DukoWindow(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0L && endMs > startMs) }
    val durationMs: Long get() = endMs - startMs
}

enum class DukoEvidence { SUPPORTED, QUALIFIED, INSUFFICIENT }
enum class DukoSessionOutcome { COMPLETED, INTERRUPTED, NEEDS_RECOVERY }

/**
 * Test-condition labels and their provenance, separate from measured clock
 * limits. A declared ClusterTune profile is never a sensor measurement.
 * Unknown is different from Off/Stock.
 */
data class DukoSessionContext(
    val game: String = "",
    val emulator: String = "",
    val graphicsDriver: String = "",
    val performanceMode: String = "",
    val fanProfile: String = "",
    val clusterTuneProfile: String = "Unknown",
    val clusterTuneEnabled: Boolean? = null,
    val tuningTool: String = "",
    val notes: String = "",
    val origin: MeasurementOrigin = MeasurementOrigin.USER_DECLARED,
) {
    fun sanitized(): DukoSessionContext = copy(
        game = game.take(120).trim(),
        emulator = emulator.take(120).trim(),
        graphicsDriver = graphicsDriver.take(120).trim(),
        performanceMode = performanceMode.take(80).trim(),
        fanProfile = fanProfile.take(80).trim(),
        clusterTuneProfile = clusterTuneProfile.take(80).trim().ifEmpty { "Unknown" },
        tuningTool = tuningTool.take(80).trim(),
        notes = notes.take(1000).trim(),
    )
}

/** A read-only hardware capability snapshot; no device ID or user identifiers. */
data class DukoCpuPolicy(
    val policyId: String,
    val relatedCpus: String,
    val hardwareMaxMHz: Long?,
    val activePolicyMaxMHz: Long?,
    val activePolicyMinMHz: Long? = null,
    val governor: String? = null,
)

data class DukoDeviceProfile(
    val capturedAtMs: Long,
    val manufacturer: String,
    val model: String,
    val soc: String,
    val androidSdk: Int,
    val androidBuild: String,
    val kernel: String,
    val cpuPolicies: List<DukoCpuPolicy>,
    val totalMemoryMiB: Long?,
    val reportedBatteryCapacityWh: Double? = null,
    val gpuClockSource: String? = null,
    val gpuLoadSource: String? = null,
    val cpuTempSource: String? = null,
    val gpuTempSource: String? = null,
    val contractVersion: Int = DukoContract.VERSION,
)

/**
 * CPU policy limits are observed directly, but their cause is unknown.
 * They are NOT evidence that a named tuner or OEM mode is active.
 */
data class DukoObservedPolicyLimits(
    val capturedElapsedMs: Long,
    val policyMaxMHz: Map<String, Long>,
    val hardwareMaxMHz: Map<String, Long>,
) {
    val restrictedPolicies: Set<String>
        get() = policyMaxMHz.filter { (key, max) ->
            val hardware = hardwareMaxMHz[key]
            hardware != null && max >= 1L && max < hardware
        }.keys
}
