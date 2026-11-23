package com.locutus5382.heimdall

import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.duko.DukoBatteryCalibration
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Persisted SQLite end_reason and v5 battery status must drive M3c confidence.
 * Neither the emulator nor Android API implies OEM electrical calibration.
 */
@RunWith(AndroidJUnit4::class)
class DukoM3cConfidenceAndroidTest {
    private suspend fun record(
        name: String,
        interrupted: Boolean,
    ): Pair<Long, DukoSessionAnalyzer> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        app.deleteDatabase(name)
        val store = LocalRecordingStore(app, name)
        val id = store.begin()
        val start = SystemClock.elapsedRealtime()
        for (i in 0..5) {
            assertTrue(store.append(id, DeviceSample(
                observedAtMs = System.currentTimeMillis() + i * 2_000L,
                cpuLoadPercent = 40.0, batteryLevelPercent = 80,
                batteryTemperatureC = 33.0, availableMemoryMiB = null,
                cpuClockMHz = 1600L, gpuClockMHz = null,
                observedElapsedMs = start + i * 2_000L,
                batteryCurrentUa = -2_000_000L, batteryVoltageMv = 4_000,
                batteryPlugged = false,
                batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            )))
        }
        if (interrupted) {
            assertEquals(id, store.recoverInterruptedSession())
        } else {
            assertTrue(store.persistStop(id))
            assertTrue(store.finish(id))
        }
        return id to DukoSessionAnalyzer(app, name)
    }

    @Test fun completedSqliteSessionKeepsMathButNeverAssumesDeviceCalibration() =
        runBlocking {
            val (id, analyzer) = record("duko_m3c_completed.db", false)
            val result = requireNotNull(analyzer.analyze(id))
            assertEquals(3, result.analysisVersion)
            assertEquals(DukoSessionOutcome.COMPLETED, result.integrity.outcome)
            assertEquals(DukoEvidence.SUPPORTED, result.energy.evidence)
            assertEquals(DukoEvidence.SUPPORTED,
                result.confidence.energyCalculationEvidence)
            assertEquals(DukoEvidence.QUALIFIED,
                result.confidence.batteryTerminalConclusion)
            assertEquals(DukoBatteryCalibration.NOT_VALIDATED,
                result.confidence.batteryCalibration)
            assertTrue("BATTERY_ELECTRICAL_CALIBRATION_NOT_VALIDATED" in
                result.confidence.warnings)
        }

    @Test fun interruptedSqliteSessionKeepsRawSeriesButCapsConclusions() =
        runBlocking {
            val (id, analyzer) = record("duko_m3c_interrupted.db", true)
            val result = requireNotNull(analyzer.analyze(id))
            assertEquals(DukoSessionOutcome.INTERRUPTED, result.integrity.outcome)
            assertEquals(DukoEvidence.SUPPORTED, result.energy.evidence)
            assertEquals(DukoEvidence.QUALIFIED,
                result.confidence.batteryTerminalConclusion)
            assertEquals(DukoEvidence.SUPPORTED,
                result.integrity.metrics[DukoMetric.CPU_LOAD]?.evidence)
            assertEquals(DukoEvidence.QUALIFIED,
                result.confidence.metricConclusions[DukoMetric.CPU_LOAD])
            assertTrue("SESSION_INTERRUPTED" in result.confidence.warnings)
        }
}
