package com.locutus5382.heimdall.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
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
 * An unfinished session is never overwritten. Recovery seals only durable
 * STOP requests, and an abandoned original remains available for review.
 */
class NativeRecorderEngine(
    private val source: DeviceSampleSource,
    private val persistence: RecordingPersistence,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 2_000L,
    // Outages are measured in elapsed time, not the number of rapid retries.
    // Five retries at 250 ms used to terminate a recoverable session after ~1 s.
    private val maximumFailureDurationMs: Long = 30_000L,
    private val retryDelayMs: Long = 250L,
    private val maximumRetryDelayMs: Long = 2_000L,
    // Kept injectable for deterministic tests without Android or wall-clock sleeps.
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(intervalMs >= 1_000L)
        require(maximumFailureDurationMs >= 1_000L)
        require(retryDelayMs >= 50L)
        require(maximumRetryDelayMs >= retryDelayMs)
    }

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
            // The new service owns no sampler: an unambiguous orphan can be
            // sealed as INTERRUPTED at its last observed sample timestamp.
            val interrupted = try {
                persistence.recoverInterruptedSession()
            } catch (_: Exception) { null }
            if (interrupted != null) {
                val count = try {
                    persistence.sampleCount(interrupted)
                } catch (_: Exception) { 0L }
                _status.value = RecorderStatus.Finished(
                    interrupted, count, reason = "INTERRUPTED"
                )
            } else {
                _status.value =
                    RecorderStatus.NeedsAttention(existing, "UNFINISHED_SESSION")
            }
        }
        _status.value
    }

    suspend fun start(): Boolean = mutex.withLock {
        if (_status.value !is RecorderStatus.Idle &&
            _status.value !is RecorderStatus.Finished &&
            !(_status.value is RecorderStatus.NeedsAttention &&
                (_status.value as RecorderStatus.NeedsAttention).sessionId == null))
            return@withLock false

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
        val sessionStart = try {
            persistence.sessionStartElapsedMs(id)
        } catch (_: Exception) { null }
        _status.value = RecorderStatus.Recording(
            id, 0L, startedElapsedMs = sessionStart
        )
        // One writer owns this session, so a successful append increments its
        // durable sample count without an O(N) COUNT(*) scan every 2 seconds.
        // STOP still queries SQLite for its authoritative final count.
        var successfulWrites = 0L
        samplerJob = scope.launch {
            var outageStartedMs: Long? = null
            var nextRetryDelayMs = retryDelayMs
            while (isActive) {
                try {
                    val sample = source.read()
                    when (persistence.appendOutcome(id, sample)) {
                        AppendOutcome.WRITTEN -> {
                            successfulWrites++
                            // One durable write closes the current outage.
                            outageStartedMs = null
                            nextRetryDelayMs = retryDelayMs
                            // Never overwrite a newer STOP/SAVING/attention state.
                            _status.update { current ->
                                if (current is RecorderStatus.Recording &&
                                    current.sessionId == id)
                                    current.copy(persistedSamples = successfulWrites, last = sample)
                                else current
                            }
                        }
                        AppendOutcome.STOP_REQUESTED -> {
                            // Normal racing tick after a durable STOP. No error.
                            break
                        }
                        AppendOutcome.RETRYABLE_ERROR -> error("SAMPLE_WRITE_FAILED")
                    }
                    delay(intervalMs)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // A transient failure is missing evidence, not a successful
                    // sample. Do not invent values or overwrite durable rows.
                    val now = monotonicNowMs()
                    val started = outageStartedMs ?: now.also { outageStartedMs = it }
                    val outageMs = (now - started).coerceAtLeast(0L)
                    if (outageMs >= maximumFailureDurationMs) {
                        // Keep the unfinished SQLite row and original ID available
                        // for review/STOP; never auto-finalize an I/O outage.
                        _status.update { current ->
                            if (current is RecorderStatus.Recording &&
                                current.sessionId == id)
                                RecorderStatus.NeedsAttention(id, "SAMPLE_IO_FAILED")
                            else current
                        }
                        break
                    }
                    delay(nextRetryDelayMs)
                    // Bounded 250 -> 500 -> 1000 -> 2000 ms retry spacing.
                    nextRetryDelayMs = if (nextRetryDelayMs >= maximumRetryDelayMs)
                        maximumRetryDelayMs
                    else (nextRetryDelayMs * 2L).coerceAtMost(maximumRetryDelayMs)
                }
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
        val activeJob = samplerJob
        activeJob?.cancel()
        val joined = if (activeJob == null) true else
            (withTimeoutOrNull(3_000L) { activeJob.join(); true } ?: false)
        if (!joined) {
            // Do not finalize while an unresponsive writer may still be active.
            _status.value = RecorderStatus.NeedsAttention(id, "STOP_TIMEOUT")
            return@withLock false
        }
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

    /** Service-only failure before a session has been created. */
    fun reportStartFailure(code: String) {
        _status.update { old ->
            if (old is RecorderStatus.Idle || old is RecorderStatus.Preparing ||
                old is RecorderStatus.Finished ||
                (old is RecorderStatus.NeedsAttention && old.sessionId == null)
            ) RecorderStatus.NeedsAttention(null, code) else old
        }
    }

    /**
     * Recover from a failure that created no durable session. START still
     * checks storage before opening any row; this cannot overwrite evidence.
     */
    suspend fun acknowledgeNoSessionFailure(): Boolean = mutex.withLock {
        val status = _status.value
        if (status is RecorderStatus.NeedsAttention && status.sessionId == null) {
            _status.value = RecorderStatus.Idle
            true
        } else false
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
