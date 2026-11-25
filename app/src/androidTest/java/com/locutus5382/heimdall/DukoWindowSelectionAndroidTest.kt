package com.locutus5382.heimdall

import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.duko.DukoEventType
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Full DB -> bounded SQL row selection -> unchanged DUKO analytics engine. */
@RunWith(AndroidJUnit4::class)
class DukoWindowSelectionAndroidTest {
    @Test fun selectedWindowUsesOnlyRequestedRowsAndPreservesOriginalRecording() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "duko_m4a_window_fixture.db"
            app.deleteDatabase(name)
            val store = LocalRecordingStore(app, name)
            val session = store.begin()
            val start = SystemClock.elapsedRealtime()
            for (i in 0..5) {
                assertTrue(store.append(session, DeviceSample(
                    observedAtMs = System.currentTimeMillis() + i * 2_000L,
                    cpuLoadPercent = 10.0 + i * 10.0,
                    batteryLevelPercent = 80, batteryTemperatureC = 33.0,
                    availableMemoryMiB = null, cpuClockMHz = null,
                    gpuClockMHz = null,
                    observedElapsedMs = start + i * 2_000L,
                    batteryCurrentUa = -2_000_000L,
                    batteryVoltageMv = 4_000,
                    batteryPlugged = false,
                    batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
                )))
            }
            assertTrue(store.persistStop(session))
            assertTrue(store.finish(session))
            val analyzer = DukoSessionAnalyzer(app, name)
            val complete = requireNotNull(analyzer.analyze(session))
            assertEquals(10_000L, complete.window.durationMs)
            assertEquals(35.0, complete.integrity.metrics
                .getValue(DukoMetric.CPU_LOAD).timeWeightedMean!!, 1e-9)
            val requested = DukoWindow(start + 2_000L, start + 6_000L)
            val selected = requireNotNull(analyzer.analyze(session, requested))
            assertEquals(requested, selected.window)
            assertEquals(4, selected.analysisVersion)
            // M1.8: selecting only a slice MUST NOT invent recording
            // start/end events at the slice's endpoints.
            val selectedEvents = requireNotNull(selected.events).events
            assertTrue(selectedEvents.any {
                it.type == DukoEventType.ANALYSIS_WINDOW_BEGIN
            })
            assertTrue(selectedEvents.any {
                it.type == DukoEventType.ANALYSIS_WINDOW_END
            })
            assertFalse(selectedEvents.any {
                it.type == DukoEventType.RECORDING_BEGIN ||
                    it.type == DukoEventType.RECORDING_COMPLETE
            })
            val fullEvents = requireNotNull(complete.events).events
            assertTrue(fullEvents.any { it.type == DukoEventType.RECORDING_BEGIN })
            assertTrue(fullEvents.any { it.type == DukoEventType.RECORDING_COMPLETE })
            assertEquals(30.0, selected.integrity.metrics
                .getValue(DukoMetric.CPU_LOAD).timeWeightedMean!!, 1e-9)
            assertEquals(3, selected.integrity.metrics
                .getValue(DukoMetric.CPU_LOAD).validSamples)
            assertEquals(1.0, selected.energy.statistics.coverage, 1e-9)
            assertEquals(8.0 * 4.0 / 3_600.0,
                selected.energy.measuredDischargeWh!!, 1e-9)
            assertEquals(DukoEvidence.QUALIFIED,
                selected.confidence.batteryTerminalConclusion)
            val misaligned = requireNotNull(analyzer.analyze(session,
                DukoWindow(start + 2_500L, start + 6_500L)))
            assertEquals(0.5, misaligned.energy.statistics.coverage, 1e-9)
            assertEquals(DukoEvidence.INSUFFICIENT, misaligned.energy.evidence)
            assertNull(analyzer.analyze(session,
                DukoWindow(start + 10_001L, start + 12_000L)))
            assertNull(analyzer.analyze(session,
                DukoWindow(start - 1L, start + 4_000L)))
            val empty = requireNotNull(analyzer.analyze(session,
                DukoWindow(start + 2_100L, start + 3_900L)))
            assertEquals(DukoEvidence.INSUFFICIENT,
                empty.integrity.metrics.getValue(DukoMetric.CPU_LOAD).evidence)
            assertNull(empty.energy.measuredDischargeWh)
            assertEquals(6L, store.sampleCount(session))
            // Re-analysis does not persist a new session or mutate raw samples.
            assertEquals(complete, analyzer.analyze(session))
        }

    @Test fun interruptedWindowRetainsReasonAndCapsMetricConfidence() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "duko_m4a_interrupted_window.db"
        app.deleteDatabase(name)
        val store = LocalRecordingStore(app, name)
        val session = store.begin()
        val start = SystemClock.elapsedRealtime()
        for (i in 0..5) {
            assertTrue(store.append(session, DeviceSample(
                observedAtMs = System.currentTimeMillis() + i * 2_000L,
                cpuLoadPercent = 40.0, batteryLevelPercent = 70,
                batteryTemperatureC = null, availableMemoryMiB = null,
                cpuClockMHz = null, gpuClockMHz = null,
                observedElapsedMs = start + i * 2_000L,
            )))
        }
        assertEquals(session, store.recoverInterruptedSession())
        val subset = requireNotNull(DukoSessionAnalyzer(app, name).analyze(
            session, DukoWindow(start + 2_000L, start + 8_000L)))
        assertEquals(DukoSessionOutcome.INTERRUPTED, subset.integrity.outcome)
        assertEquals(DukoEvidence.SUPPORTED,
            subset.integrity.metrics.getValue(DukoMetric.CPU_LOAD).evidence)
        assertEquals(DukoEvidence.QUALIFIED,
            subset.confidence.metricConclusions[DukoMetric.CPU_LOAD])
        assertTrue("SESSION_INTERRUPTED" in subset.confidence.warnings)
        assertTrue(requireNotNull(subset.events).events.any {
            it.type == DukoEventType.ANALYSIS_WINDOW_END
        })
        assertFalse(requireNotNull(subset.events).events.any {
            it.type == DukoEventType.RECORDING_INTERRUPTED
        })
    }
}
