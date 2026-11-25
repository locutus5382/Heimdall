package com.locutus5382.heimdall.engine

import org.junit.Assert.*
import org.junit.Test

/** Pure deterministic K gates: never use CI runner wall time as a pass budget. */
class RecorderOverheadMonitorTest {
    @Test fun recorderTimeDistributionTracksBoundedUpperBinsAndRealMaxima() {
        val monitor = RecorderOverheadMonitor()
        monitor.begin(11, 2_000L)
        for (i in 0 until 100) {
            val read = if (i == 99) 2_100L else 3L
            val write = if (i == 99) 500L else 5L
            monitor.tick(11, read, write, read + write,
                deadlineLatenessMs = if (i == 99) 24L else 0L,
                missedDeadlines = if (i == 99) 1L else 0L,
                processCpuDeltaMs = 2L)
        }
        val live = requireNotNull(monitor.snapshot())
        assertEquals(100L, live.ticks)
        assertEquals(3L, live.readP95UpperMs)
        assertEquals(5L, live.appendP95UpperMs)
        assertEquals(8L, live.totalP95UpperMs)
        assertEquals(2_100L, live.maxReadMs)
        assertEquals(500L, live.maxAppendMs)
        assertEquals(2_600L, live.maxTotalMs)
        assertEquals(1L, live.missedDeadlines)
        assertEquals(1L, live.lateTicks)
        assertEquals(24L, live.maxDeadlineLatenessMs)
        assertEquals(200L, live.processCpuWindowMs)
        assertFalse(live.completed)
        monitor.end(11)
        assertTrue(requireNotNull(monitor.snapshot()).completed)
    }

    @Test fun readsFailIndependentlyFromAppendAndDoNotCreateFakeDurations() {
        val observer = RecorderOverheadMonitor()
        observer.begin(21L, 2_000L)
        observer.failedTick(21L, duringAppend = false)
        observer.failedTick(21L, duringAppend = true)
        observer.tick(999, 6L, 5L, 11L, 0, 0)
        observer.tick(21, -1L, 5L, 11L, 0, 0)
        val snapshot = requireNotNull(observer.snapshot())
        assertEquals(0L, snapshot.ticks)
        assertEquals(1L, snapshot.failedReads)
        assertEquals(1L, snapshot.failedAppends)
        assertNull(snapshot.readP50UpperMs)
        assertNull(snapshot.appendP95UpperMs)
        assertEquals(0L, snapshot.maxTotalMs)
        assertNull(snapshot.processCpuWindowMs)
    }

    @Test fun onlyOneSessionRemainsInMemoryAndOldSessionsCannotMutateNewOne() {
        val observer = RecorderOverheadMonitor()
        observer.begin(1L, 2_000L)
        repeat(200_000) {
            observer.tick(1L, 8L, 9L, 17L, 0L, 0L)
        }
        assertEquals(200_000L, observer.snapshot()!!.ticks)
        observer.end(1L)
        observer.begin(2L, 2_000L)
        observer.tick(1L, 1000, 1000, 2000, 0L, 0L)
        observer.end(1L)
        assertEquals(0L, observer.snapshot()!!.ticks)
        assertEquals(2L, observer.snapshot()!!.sessionId)
        observer.tick(2L, 1L, 4L, 5L, 0, 0)
        observer.end(2L)
        assertEquals(1L, observer.snapshot()!!.ticks)
        observer.clear()
        assertNull(observer.snapshot())
    }

    @Test fun processCpuUnknownIsNotSilentlyCoercedToZero() {
        val observer = RecorderOverheadMonitor()
        observer.begin(15L, 2_000L)
        observer.tick(15L, 2L, 4L, 6L, 0L, 0L,
            processCpuDeltaMs = null)
        assertNull(observer.snapshot()!!.processCpuWindowMs)
        observer.tick(15L, 2L, 4L, 6L, 0L, 0L,
            processCpuDeltaMs = 5L)
        assertEquals(5L, observer.snapshot()!!.processCpuWindowMs)
    }

    @Test fun pathologicalLatencyUsesOverflowBucketInsteadOfTruncatingMaximum() {
        val observer = RecorderOverheadMonitor()
        observer.begin(19L, 2_000L)
        observer.tick(19L, 120_000L, 25L, 120_025L, 12L, 60L)
        val result = observer.snapshot()!!
        assertEquals(Long.MAX_VALUE, result.readP95UpperMs)
        assertEquals(120_000L, result.maxReadMs)
        assertEquals(60L, result.missedDeadlines)
    }
}
