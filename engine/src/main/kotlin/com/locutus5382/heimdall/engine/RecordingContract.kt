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
    data class Finished(val sessionId: Long, val samples: Long) : RecorderStatus
    data class NeedsAttention(val sessionId: Long?, val code: String) : RecorderStatus
}

interface DeviceSampleSource {
    /** Every source is best-effort/read-only. Unsupported metrics are null. */
    suspend fun read(): DeviceSample
}

interface RecordingPersistence {
    /** Any incomplete row or unacknowledged lease blocks a fresh START. */
    suspend fun unresolvedSessionId(): Long?
    /** Finish only the original session with a previously persisted STOP intent.
     * Returns its ID on successful recovery, null when no durable STOP exists
     * or when the original cannot yet be finalized. Never opens another row.
     */
    suspend fun recoverPendingStop(): Long?
    /** Creates one session row and its matching recovery lease. */
    suspend fun begin(): Long
    suspend fun append(sessionId: Long, sample: DeviceSample): Boolean
    /** Durable terminal intent. A false result forbids finalization. */
    suspend fun persistStop(sessionId: Long): Boolean
    /** Terminal row CAS followed by owner-matched lease cleanup. Retry-safe. */
    suspend fun finish(sessionId: Long): Boolean
    suspend fun sampleCount(sessionId: Long): Long
}
