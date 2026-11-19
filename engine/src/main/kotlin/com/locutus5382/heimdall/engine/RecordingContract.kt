package com.locutus5382.heimdall.engine

/**
 * Android-free recording vocabulary. No old CoreMetrics service, statuses or
 * application globals. A new session must never replace an unfinished row.
 */
data class DeviceSample(
    val observedAtMs: Long,
    val cpuLoadPercent: Double?,
    val batteryLevelPercent: Int?,
    val batteryTemperatureC: Double?,
    val availableMemoryMiB: Long?,
    val cpuClockMHz: Long?,
    val gpuClockMHz: Long?,
    // Added in v3; null is the correct representation for unsupported sources.
    val observedElapsedMs: Long? = null,
    val thermalStatus: Int? = null,
    val thermalHeadroom: Double? = null,
    val batteryCurrentUa: Long? = null,
    val batteryVoltageMv: Int? = null,
    val batteryPlugged: Boolean? = null,
    val gpuLoadPercent: Double? = null,
)

sealed interface RecorderStatus {
    data object Idle : RecorderStatus
    data object Preparing : RecorderStatus
    data class Recording(
        val sessionId: Long,
        val persistedSamples: Long,
        val last: DeviceSample? = null,
    ) : RecorderStatus
    data class Stopping(val sessionId: Long) : RecorderStatus
    data class Saving(val sessionId: Long) : RecorderStatus
    data class Finished(
        val sessionId: Long, val samples: Long,
        val reason: String = "COMPLETED"
    ) : RecorderStatus
    data class NeedsAttention(val sessionId: Long?, val code: String) : RecorderStatus
}

interface DeviceSampleSource {
    /** Every source is best-effort/read-only. Unsupported metrics are null. */
    suspend fun read(): DeviceSample
}

/** A denied append after durable STOP is an expected race, not an I/O failure. */
enum class AppendOutcome { WRITTEN, STOP_REQUESTED, RETRYABLE_ERROR }

interface RecordingPersistence {
    /** Any incomplete row or unacknowledged lease blocks a fresh START. */
    suspend fun unresolvedSessionId(): Long?
    /** Finish only the original session with a previously persisted STOP intent.
     * Returns its ID on successful recovery, null when no durable STOP exists
     * or when the original cannot yet be finalized. Never opens another row.
     */
    suspend fun recoverPendingStop(): Long?
    /** Seal an abandoned (no active sampler) session as INTERRUPTED, never
     * inventing samples or replacing its ID; null if ownership is ambiguous.
     * Legacy test doubles may retain the older explicit review flow. */
    suspend fun recoverInterruptedSession(): Long? = null
    /** Creates one session row and its matching recovery lease. */
    suspend fun begin(): Long
    suspend fun append(sessionId: Long, sample: DeviceSample): Boolean
    /** Adapter for older fake stores. The SQLite implementation overrides
     * this to distinguish stopped sessions from transient write failures. */
    suspend fun appendOutcome(sessionId: Long, sample: DeviceSample): AppendOutcome =
        if (append(sessionId, sample)) AppendOutcome.WRITTEN
        else AppendOutcome.RETRYABLE_ERROR
    /** Durable terminal intent. A false result forbids finalization. */
    suspend fun persistStop(sessionId: Long): Boolean
    /** Terminal row CAS followed by owner-matched lease cleanup. Retry-safe. */
    suspend fun finish(sessionId: Long): Boolean
    suspend fun sampleCount(sessionId: Long): Long
}
