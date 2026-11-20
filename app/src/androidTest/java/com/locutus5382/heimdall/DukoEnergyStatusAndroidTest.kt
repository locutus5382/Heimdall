package com.locutus5382.heimdall

import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the SQLite v5 battery_status column reaches pure DUKO analysis. */
@RunWith(AndroidJUnit4::class)
class DukoEnergyStatusAndroidTest {
    @Test fun savedBatteryStatusIsUsedAndOldNullStatusStaysQualified() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "duko_m3b_status_confidence_fixture.db"
        app.deleteDatabase(name)
        val recorder = LocalRecordingStore(app, name)
        val start = SystemClock.elapsedRealtime()
        val validSession = recorder.begin()
        for (i in 0..5) {
            assertTrue(recorder.append(validSession, DeviceSample(
                observedAtMs = System.currentTimeMillis() + i * 2_000L,
                cpuLoadPercent = null, batteryLevelPercent = 80,
                batteryTemperatureC = 34.0, availableMemoryMiB = null,
                cpuClockMHz = null, gpuClockMHz = null,
                observedElapsedMs = start + i * 2_000L,
                batteryCurrentUa = -2_000_000L, batteryVoltageMv = 4_000,
                batteryPlugged = false,
                batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
            )))
        }
        assertTrue(recorder.persistStop(validSession))
        assertTrue(recorder.finish(validSession))
        val analyzer = DukoSessionAnalyzer(app, name)
        val measured = requireNotNull(analyzer.analyze(validSession)).energy
        assertEquals(DukoEvidence.SUPPORTED, measured.evidence)
        assertEquals(8.0, measured.timeWeightedDischargeWatts!!, 1e-9)
        assertEquals(0, measured.unknownBatteryStatusSamples)

        val oldSession = recorder.begin()
        for (i in 0..5) {
            assertTrue(recorder.append(oldSession, DeviceSample(
                observedAtMs = System.currentTimeMillis() + i * 2_000L,
                cpuLoadPercent = null, batteryLevelPercent = 78,
                batteryTemperatureC = 34.0, availableMemoryMiB = null,
                cpuClockMHz = null, gpuClockMHz = null,
                observedElapsedMs = start + 20_000L + i * 2_000L,
                batteryCurrentUa = -2_000_000L, batteryVoltageMv = 4_000,
                batteryPlugged = false, batteryStatus = null,
            )))
        }
        assertTrue(recorder.persistStop(oldSession))
        assertTrue(recorder.finish(oldSession))
        val legacy = requireNotNull(analyzer.analyze(oldSession)).energy
        assertEquals(DukoEvidence.QUALIFIED, legacy.evidence)
        assertEquals(6, legacy.unknownBatteryStatusSamples)
    }
}
