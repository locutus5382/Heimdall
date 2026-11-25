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
    private val overhead: RecorderOverheadSink = NoopRecorderOverheadSink,
    // Android may supply process-wide CPU ms. It is an upper bound during
    // the tick window, including other threads, not isolated recorder CPU.
    private val processCpuNowMs: () -> Long? = { null },
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
    // Guarded by mutex. STOP invalidates an in-flight START preparation,
    // even when a vendor sysfs reader has not returned yet.
    private var startGeneration: Long = 0L
    private suspend fun failPreparing(generation: Long, code: String) {
        mutex.withLock {
            if (generation == startGeneration &&
                _status.value is RecorderStatus.Preparing) {
                _status.value = RecorderStatus.NeedsAttention(null, code)
            }
        }
    }
    private suspend fun abandonPreparing(generation: Long) {
        mutex.withLock {
            if (generation == startGeneration &&
                _status.value is RecorderStatus.Preparing) {
                startGeneration++
                _status.value = RecorderStatus.Idle
            }
        }
    }

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
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // An exceptional STOP recovery is NOT proof that no durable STOP
            // exists. Do not fall through to interrupted-session recovery.
            _status.value = RecorderStatus.NeedsAttention(existing, "STOP_RECOVERY_FAILED")
            return@withLock _status.value
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

    suspend fun start(): Boolean {
        val generation = mutex.withLock {
            if (_status.value !is RecorderStatus.Idle &&
                _status.value !is RecorderStatus.Finished &&
                !(_status.value is RecorderStatus.NeedsAttention &&
                    (_status.value as RecorderStatus.NeedsAttention).sessionId == null))
                return@withLock null

            _status.value = RecorderStatus.Preparing
            ++startGeneration
        } ?: return false

        // All sysfs discovery runs BEFORE the command mutex is acquired for
        // the short durable commit. A STOP during this phase invalidates the
        // generation and prevents an eventual stale START from opening a row.
        var failureCode = "STORAGE_UNAVAILABLE"
        try {
            val existing = persistence.unresolvedSessionId()
            if (existing != null) {
                mutex.withLock {
                    if (generation == startGeneration &&
                        _status.value is RecorderStatus.Preparing) {
                        _status.value =
                            RecorderStatus.NeedsAttention(existing, "UNFINISHED_SESSION")
                    }
                }
                return false
            }

            failureCode = "SESSION_PREPARATION_FAILED"
            val preparation = persistence.prepareStart()
            failureCode = "SESSION_CREATE_FAILED"
            return mutex.withLock {
                if (generation != startGeneration ||
                    _status.value !is RecorderStatus.Preparing) return@withLock false

                // The SQLite implementation validates unresolved rows again in
                // the transaction, so a concurrent writer cannot replace one.
                val id = persistence.begin(preparation)
                if (id <= 0L) {
                    _status.value =
                        RecorderStatus.NeedsAttention(null, "INVALID_SESSION_ID")
                    return@withLock false
                }
                val sessionStart = try {
                    persistence.sessionStartElapsedMs(id)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { null }
                _status.value = RecorderStatus.Recording(
                    id, 0L, startedElapsedMs = sessionStart
                )
                // Only durable appended rows count; never silently synthesize
                // a sample or replace an unfinished original session.
                var successfulWrites = 0L
                runCatching { overhead.begin(id, intervalMs) }
                samplerJob = scope.launch {
                    var outageStartedMs: Long? = null
                    var nextRetryDelayMs = retryDelayMs
                    var nextTickMs = monotonicNowMs()
                    while (isActive) {
                        val tickStartMs = monotonicNowMs()
                        val cpuAtStart = runCatching { processCpuNowMs() }.getOrNull()
                        val lateMs = (tickStartMs - nextTickMs).coerceAtLeast(0L)
                        var duringAppend = false
                        try {
                            val sample = source.read()
                            val readDoneMs = monotonicNowMs()
                            duringAppend = true
                            when (persistence.appendOutcome(id, sample)) {
                                AppendOutcome.WRITTEN -> {
                                    val writeDoneMs = monotonicNowMs()
                                    val scheduled = nextTickMs + intervalMs
                                    val skipped = if (writeDoneMs - scheduled > intervalMs)
                                        ((writeDoneMs - scheduled) / intervalMs)
                                            .coerceAtLeast(0L) else 0L
                                    val cpuAtEnd = runCatching {
                                        processCpuNowMs()
                                    }.getOrNull()
                                    // No disk logs, no per-tick report snapshots
                                    // and no second sensor/database read.
                                    runCatching {
                                        overhead.tick(
                                            sessionId = id,
                                            readMs = (readDoneMs - tickStartMs).coerceAtLeast(0L),
                                            appendMs = (writeDoneMs - readDoneMs).coerceAtLeast(0L),
                                            totalMs = (writeDoneMs - tickStartMs).coerceAtLeast(0L),
                                            deadlineLatenessMs = lateMs,
                                            missedDeadlines = skipped,
                                            processCpuDeltaMs =
                                                if (cpuAtStart != null && cpuAtEnd != null &&
                                                    cpuAtEnd >= cpuAtStart)
                                                    cpuAtEnd - cpuAtStart else null,
                                        )
                                    }
                                    successfulWrites++
                                    outageStartedMs = null
                                    nextRetryDelayMs = retryDelayMs
                                    _status.update { current ->
                                        if (current is RecorderStatus.Recording &&
                                            current.sessionId == id)
                                            current.copy(persistedSamples = successfulWrites,
                                                last = sample)
                                        else current
                                    }
                                }
                                AppendOutcome.STOP_REQUESTED -> break
                                AppendOutcome.RETRYABLE_ERROR -> error("SAMPLE_WRITE_FAILED")
                            }
                            // Deadline scheduling avoids adding sysfs+SQLite
                            // processing latency to every sampling interval.
                            val now = monotonicNowMs()
                            val scheduled = nextTickMs + intervalMs
                            nextTickMs = if (now - scheduled > intervalMs)
                                now + intervalMs else scheduled
                            delay((nextTickMs - now).coerceAtLeast(0L))
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            runCatching { overhead.failedTick(id, duringAppend) }
                            // Count a slow failing read/write in the outage
                            // budget, not only the period after it throws.
                            val now = monotonicNowMs()
                            val started = outageStartedMs
                                ?: tickStartMs.also { outageStartedMs = it }
                            if ((now - started).coerceAtLeast(0L) >=
                                maximumFailureDurationMs) {
                                _status.update { current ->
                                    if (current is RecorderStatus.Recording &&
                                        current.sessionId == id)
                                        RecorderStatus.NeedsAttention(id, "SAMPLE_IO_FAILED")
                                    else current
                                }
                                break
                            }
                            delay(nextRetryDelayMs)
                            nextRetryDelayMs =
                                if (nextRetryDelayMs >= maximumRetryDelayMs)
                                    maximumRetryDelayMs
                                else (nextRetryDelayMs * 2L).coerceAtMost(
                                    maximumRetryDelayMs)
                            // A recovered tick establishes a fresh deadline.
                            nextTickMs = monotonicNowMs()
                        }
                    }
                }
                true
            }
        } catch (cancel: CancellationException) {
            abandonPreparing(generation)
            throw cancel
        } catch (_: Exception) {
            failPreparing(generation, failureCode)
            return false
        }
    }

    suspend fun stop(): Boolean = mutex.withLock {
        val state = _status.value
        if (state is RecorderStatus.Preparing) {
            // There is no session ID to finalize yet; invalidate the pending
            // hardware preflight and prevent a delayed commit.
            startGeneration++
            _status.value = RecorderStatus.Idle
            return@withLock true
        }
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
        runCatching { overhead.end(id) }
        true
    }

    /** Service-only failure before a session has been created. */
    suspend fun reportStartFailure(code: String) = mutex.withLock {
        val old = _status.value
        if (old is RecorderStatus.Idle || old is RecorderStatus.Preparing ||
            old is RecorderStatus.Finished ||
            (old is RecorderStatus.NeedsAttention && old.sessionId == null)) {
            // A foreground-start rejection must not allow an older preflight
            // to commit once foreground ownership has been denied.
            startGeneration++
            _status.value = RecorderStatus.NeedsAttention(null, code)
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
        val active = samplerJob
        active?.cancel()
        val joined = if (active == null) true else
            (withTimeoutOrNull(2_000L) { active.join(); true } ?: false)
        if (joined) samplerJob = null
        if (id != null) {
            _status.value = RecorderStatus.NeedsAttention(id, "SERVICE_INTERRUPTED")
            runCatching { overhead.end(id) }
        }
    }
}
