package com.locutus5382.heimdall.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeRecorderEngineTest {
    private class FakeStore : RecordingPersistence {
        var active: Long? = null
        var nextId = 7L
        var writes = 0L
        var stopCalls = 0
        var finishes = 0
        var failStop = false
        var failFinish = false
        var stopDurable = false
        override suspend fun unresolvedSessionId() = active
        override suspend fun recoverPendingStop(): Long? {
            val id = active ?: return null
            if (!stopDurable) return null
            return if (finish(id)) id else null
        }
        override suspend fun begin(): Long {
            check(active == null)
            val id = nextId++
            active = id
            return id
        }
        override suspend fun append(sessionId: Long, sample: DeviceSample): Boolean {
            check(active == sessionId)
            writes++
            return true
        }
        override suspend fun persistStop(sessionId: Long): Boolean {
            stopCalls++
            if (failStop || active != sessionId) return false
            stopDurable = true
            return true
        }
        override suspend fun finish(sessionId: Long): Boolean {
            if (failFinish || !stopDurable || active != sessionId) return false
            finishes++
            active = null
            return true
        }
        override suspend fun sampleCount(sessionId: Long) = writes
    }
    private class FakeSource : DeviceSampleSource {
        var calls = 0
        var fail = false
        var transientFailures = 0
        override suspend fun read(): DeviceSample {
            calls++
            if (fail || calls <= transientFailures) throw IllegalStateException("no sensor")
            return DeviceSample(1L, 17.0, 60, 31.0, 2200, 1600, null)
        }
    }
    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(2_000) { while (!predicate()) delay(5) }
    }

    @Test fun startSavesRealSamplesAndStopFinalizesSameId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore()
            val e = NativeRecorderEngine(FakeSource(), store, scope, 1_000)
            assertTrue(e.start())
            await { store.writes > 0 }
            assertFalse(e.start())
            val id = (e.status.value as RecorderStatus.Recording).sessionId
            assertTrue(e.stop())
            assertEquals(id, (e.status.value as RecorderStatus.Finished).sessionId)
            assertEquals(1, store.finishes)
            assertNull(store.active)
            val after = store.writes
            delay(35)
            assertEquals(after, store.writes)
        } finally { scope.cancel() }
    }

    @Test fun unresolvedOriginalSessionBlocksReplacementStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore().apply { active = 82L }
            val e = NativeRecorderEngine(FakeSource(), store, scope)
            assertFalse(e.start())
            assertEquals("UNFINISHED_SESSION",
                (e.status.value as RecorderStatus.NeedsAttention).code)
            assertEquals(82L, store.active)
            assertEquals(7L, store.nextId)
        } finally { scope.cancel() }
    }

    @Test fun failedStopNeverFinalizesBeforeDurableIntent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore().apply { failStop = true }
            val e = NativeRecorderEngine(FakeSource(), store, scope)
            assertTrue(e.start())
            await { store.writes > 0 }
            assertFalse(e.stop())
            assertEquals(0, store.finishes)
            assertEquals(7L, store.active)
            assertEquals("STOP_NOT_DURABLE",
                (e.status.value as RecorderStatus.NeedsAttention).code)
            store.failStop = false
            assertTrue(e.stop())
            assertEquals(1, store.finishes)
        } finally { scope.cancel() }
    }

    @Test fun failedFinalizationCanRetryWithoutNewSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore().apply { failFinish = true }
            val e = NativeRecorderEngine(FakeSource(), store, scope)
            assertTrue(e.start())
            await { store.writes > 0 }
            assertFalse(e.stop())
            assertEquals("FINALIZATION_FAILED",
                (e.status.value as RecorderStatus.NeedsAttention).code)
            assertEquals(7L, store.active)
            store.failFinish = false
            assertTrue(e.stop())
            assertNull(store.active)
            assertEquals(1, store.finishes)
            assertEquals(8L, store.nextId)
        } finally { scope.cancel() }
    }

    @Test fun sensorFailureStopsSamplerWithoutClearingSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore()
            val e = NativeRecorderEngine(FakeSource().apply { fail = true },
                store, scope)
            assertTrue(e.start())
            await { e.status.value is RecorderStatus.NeedsAttention }
            assertEquals("SAMPLE_IO_FAILED",
                (e.status.value as RecorderStatus.NeedsAttention).code)
            assertEquals(7L, store.active)
            assertEquals(0, store.finishes)
            assertTrue(e.stop())
            assertNull(store.active)
        } finally { scope.cancel() }
    }

    @Test fun intermittentSensorFailuresRecoverWithoutReplacingSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore()
            val e = NativeRecorderEngine(
                FakeSource().apply { transientFailures = 3 }, store, scope,
                intervalMs = 1_000L, retryDelayMs = 60L
            )
            assertTrue(e.start())
            await { store.writes > 0 }
            val active = e.status.value as RecorderStatus.Recording
            assertEquals(7L, active.sessionId)
            assertEquals(1L, active.persistedSamples)
            assertTrue(e.stop())
            assertNull(store.active)
            assertEquals(1, store.finishes)
        } finally { scope.cancel() }
    }

    @Test fun recoverableNoSessionErrorCanRetryStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore()
            val e = NativeRecorderEngine(FakeSource(), store, scope, 1_000L)
            e.reportStartFailure("FOREGROUND_NOT_ALLOWED")
            assertEquals(null, (e.status.value as RecorderStatus.NeedsAttention).sessionId)
            assertTrue(e.start())
            await { store.writes > 0L }
            assertTrue(e.stop())
            assertEquals(1, store.finishes)
        } finally { scope.cancel() }
    }

    @Test fun serviceDestructionRetainsUnfinishedRowAndStopsSampling() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore()
            val e = NativeRecorderEngine(FakeSource(), store, scope)
            assertTrue(e.start())
            await { store.writes > 0 }
            e.shutdownForServiceDeath()
            assertEquals("SERVICE_INTERRUPTED",
                (e.status.value as RecorderStatus.NeedsAttention).code)
            assertEquals(7L, store.active)
            assertEquals(0, store.finishes)
            val after = store.writes
            delay(35)
            assertEquals(after, store.writes)
        } finally { scope.cancel() }
    }

    @Test fun restartRecoversDurableStopForOriginalSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore().apply {
                active = 82L
                stopDurable = true
                writes = 12L
            }
            val engine = NativeRecorderEngine(FakeSource(), store, scope)
            val restored = engine.inspectExisting() as RecorderStatus.Finished
            assertEquals(82L, restored.sessionId)
            assertEquals(12L, restored.samples)
            assertNull(store.active)
            assertEquals(1, store.finishes)
            assertEquals(7L, store.nextId) // A replacement session was not created.
        } finally { scope.cancel() }
    }

    @Test fun restartWithoutStopRetainsOriginalUntilUserStops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore().apply { active = 82L }
            val engine = NativeRecorderEngine(FakeSource(), store, scope)
            val status = engine.inspectExisting() as RecorderStatus.NeedsAttention
            assertEquals(82L, status.sessionId)
            assertEquals("UNFINISHED_SESSION", status.code)
            assertFalse(engine.start())
            assertEquals(82L, store.active)
            assertTrue(engine.stop())
            assertEquals(82L, (engine.status.value as RecorderStatus.Finished).sessionId)
            assertEquals(7L, store.nextId)
        } finally { scope.cancel() }
    }

    @Test fun failedAutomaticFinalizationPreservesEvidenceForManualStopRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FakeStore().apply {
                active = 82L
                stopDurable = true
                failFinish = true
            }
            val engine = NativeRecorderEngine(FakeSource(), store, scope)
            assertTrue(engine.inspectExisting() is RecorderStatus.NeedsAttention)
            assertEquals(82L, store.active)
            assertEquals(0, store.finishes)
            store.failFinish = false
            assertTrue(engine.stop())
            assertNull(store.active)
            assertEquals(1, store.finishes)
        } finally { scope.cancel() }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun tenSecondSensorOutageRecoversWithoutReplacingSession() = runTest {
        val store = FakeStore()
        val source = object : DeviceSampleSource {
            override suspend fun read(): DeviceSample {
                if (testScheduler.currentTime < 10_000L) {
                    error("TEMPORARY_SENSOR_UNAVAILABLE")
                }
                return DeviceSample(1L, 17.0, 60, 31.0, 2200L, 1600L, null)
            }
        }
        val engine = NativeRecorderEngine(
            source, store, backgroundScope,
            intervalMs = 1_000L,
            maximumFailureDurationMs = 30_000L,
            monotonicNowMs = { testScheduler.currentTime }
        )
        assertTrue(engine.start())
        runCurrent()
        advanceTimeBy(13_000L)
        runCurrent()
        val recording = engine.status.value as RecorderStatus.Recording
        assertEquals(7L, recording.sessionId)
        assertTrue("Recorder must resume appending after a 10 s outage", store.writes > 0L)
        assertEquals(7L, store.active)
        assertEquals(0, store.finishes)
        assertTrue(engine.stop())
        assertEquals(1, store.finishes)
        assertNull(store.active)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun continuousSixtySecondSensorOutageDoesNotSilentlyFinalize() = runTest {
        val store = FakeStore()
        val source = object : DeviceSampleSource {
            override suspend fun read(): DeviceSample =
                error("PERSISTENT_SENSOR_UNAVAILABLE")
        }
        val engine = NativeRecorderEngine(
            source, store, backgroundScope,
            intervalMs = 1_000L,
            maximumFailureDurationMs = 30_000L,
            monotonicNowMs = { testScheduler.currentTime }
        )
        assertTrue(engine.start())
        runCurrent()
        advanceTimeBy(25_000L)
        runCurrent()
        assertTrue("25 s outage must still be recoverable",
            engine.status.value is RecorderStatus.Recording)
        advanceTimeBy(35_000L)
        runCurrent()
        val state = engine.status.value as RecorderStatus.NeedsAttention
        assertEquals(7L, state.sessionId)
        assertEquals("SAMPLE_IO_FAILED", state.code)
        assertEquals(7L, store.active)
        assertEquals(0, store.finishes)
        assertEquals(0L, store.writes)
        assertTrue("User STOP must still finalize original evidence row", engine.stop())
        assertNull(store.active)
        assertEquals(1, store.finishes)
    }

}
