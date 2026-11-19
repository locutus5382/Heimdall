package com.locutus5382.heimdall.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sole recorder coordinator. Commands are serialized. The sampling job
 * NEVER creates/finalizes rows or clears a lease; only this coordinator may.
 *
 * No automatic process-death recovery in v0.1: unfinished evidence is kept,
 * and a new START is refused. Recovery will be added with explicit tests.
 */
class NativeRecorderEngine(
    private val source: DeviceSampleSource,
    private val persistence: RecordingPersistence,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 2_000L,
) {
    init { require(intervalMs >= 1_000L) }

    private val mutex = Mutex()
    private val _status = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
    val status: StateFlow<RecorderStatus> = _status
    private var samplerJob: Job? = null

    /**
     * Read-only launch inspection, except for a STOP that was durable BEFORE
     * the previous process/service died. Never replaces an unfinished session.
     */
    suspend fun inspectExisting(): RecorderStatus = mutex.withLock {
        if (_status.value !is RecorderStatus.Idle) return@withLock _status.value

        val existing = try {
            persistence.unresolvedSessionId()
        } catch (_: Exception) {
            _status.value = RecorderStatus.NeedsAttention(null, "STORAGE_UNAVAILABLE")
            return@withLock _status.value
        }
        if (existing == null) return@withLock _status.value

        val recovered = try {
            persistence.recoverPendingStop()
        } catch (_: Exception) {
            null // Original session and its lease remain; STOP may be retried.
        }
        if (recovered != null) {
            val count = try {
                persistence.sampleCount(recovered)
            } catch (_: Exception) { 0L }
            _status.value = RecorderStatus.Finished(recovered, count)
        } else {
            _status.value = RecorderStatus.NeedsAttention(existing, "UNFINISHED_SESSION")
        }
        _status.value
    }

    suspend fun start(): Boolean = mutex.withLock {
        if (_status.value !is RecorderStatus.Idle &&
            _status.value !is RecorderStatus.Finished) return@withLock false

        _status.value = RecorderStatus.Preparing
        val existing = try {
            persistence.unresolvedSessionId()
        } catch (_: Exception) {
            _status.value = RecorderStatus.NeedsAttention(null, "STORAGE_UNAVAILABLE")
            return@withLock false
        }
        if (existing != null) {
            _status.value = RecorderStatus.NeedsAttention(existing, "UNFINISHED_SESSION")
            return@withLock false
        }
        val id = try {
            persistence.begin()
        } catch (_: Exception) {
            _status.value = RecorderStatus.NeedsAttention(null, "SESSION_CREATE_FAILED")
            return@withLock false
        }
        if (id <= 0L) {
            _status.value = RecorderStatus.NeedsAttention(null, "INVALID_SESSION_ID")
            return@withLock false
        }
        _status.value = RecorderStatus.Recording(id, 0L)
        samplerJob = scope.launch {
            while (isActive) {
                try {
                    val sample = source.read()
                    if (!persistence.append(id, sample)) {
                        throw IllegalStateException("SAMPLE_WRITE_FAILED")
                    }
                    val saved = persistence.sampleCount(id)
                    val current = _status.value
                    if (current is RecorderStatus.Recording &&
                        current.sessionId == id) {
                        _status.value = current.copy(
                            persistedSamples = saved, last = sample)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Never independently finalize/clear a session here.
                    // Leave its original row for a user-initiated STOP retry.
                    _status.value = RecorderStatus.NeedsAttention(
                        id, "SAMPLE_IO_FAILED")
                    break
                }
                delay(intervalMs)
            }
        }
        true
    }

    suspend fun stop(): Boolean = mutex.withLock {
        val state = _status.value
        val id = when (state) {
            is RecorderStatus.Recording -> state.sessionId
            is RecorderStatus.Stopping -> state.sessionId
            is RecorderStatus.Saving -> state.sessionId
            is RecorderStatus.NeedsAttention -> state.sessionId
            else -> null
        } ?: return@withLock false
        if (id <= 0L) return@withLock false

        _status.value = RecorderStatus.Stopping(id)
        val durable = try {
            persistence.persistStop(id)
        } catch (_: Exception) {
            false
        }
        // Always stop a possibly in-flight writer even if STOP persistence
        // fails. Do NOT finalize a row without durable terminal intent.
        samplerJob?.cancelAndJoin()
        samplerJob = null
        if (!durable) {
            _status.value = RecorderStatus.NeedsAttention(id, "STOP_NOT_DURABLE")
            return@withLock false
        }
        _status.value = RecorderStatus.Saving(id)
        val finished = try {
            persistence.finish(id)
        } catch (_: Exception) {
            false
        }
        if (!finished) {
            _status.value = RecorderStatus.NeedsAttention(id, "FINALIZATION_FAILED")
            return@withLock false
        }
        val total = try { persistence.sampleCount(id) } catch (_: Exception) { 0L }
        _status.value = RecorderStatus.Finished(id, total)
        true
    }

    /** No service may leave its coroutine sampling without a foreground owner. */
    suspend fun shutdownForServiceDeath() = mutex.withLock {
        val current = _status.value
        val id = when (current) {
            is RecorderStatus.Recording -> current.sessionId
            is RecorderStatus.Stopping -> current.sessionId
            is RecorderStatus.Saving -> current.sessionId
            else -> null
        }
        samplerJob?.cancelAndJoin()
        samplerJob = null
        if (id != null) _status.value =
            RecorderStatus.NeedsAttention(id, "SERVICE_INTERRUPTED")
    }
}
