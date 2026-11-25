package com.locutus5382.heimdall

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * K synthetic SQLite throughput smoke benchmark, NOT a hardware-performance
 * certification. CI emulator timing is logged but does not enforce a
 * device-specific CPU/PSS overhead percentage; the Odin A/B protocol does.
 *
 * No network, root, screen capture or playback. Unique disposable DB.
 */
@RunWith(AndroidJUnit4::class)
class RecorderOverheadBenchmarkAndroidTest {
    private val tag = "Heimdall_K_Benchmark"

    @Test fun boundedSyntheticRecordingReportsLatencyAndPreservesEverySample() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "heimdall_k_benchmark_fixture.db"
            app.deleteDatabase(name)
            try {
                val store = LocalRecordingStore(app, name)
                val id = store.begin()
                val startAt = SystemClock.elapsedRealtime()
                val durations = LongArray(80)
                val processCpuStart = Process.getElapsedCpuTime()
                val pssBefore = Debug.getPss()
                for (i in durations.indices) {
                    val sample = DeviceSample(
                        observedAtMs = 1_900_000_000_000L + 2_000L * i,
                        observedElapsedMs = startAt + 2_000L * i,
                        cpuLoadPercent = 32.5 + i % 20,
                        batteryLevelPercent = 80,
                        batteryTemperatureC = 36.0,
                        availableMemoryMiB = 3_000L,
                        cpuClockMHz = 1_600L,
                        gpuClockMHz = 550L,
                        cpuCoreLoadPercent = mapOf(0 to 75.0, 1 to 35.0),
                        cpuPolicyClocksMHz = mapOf("policy0" to 1_500L),
                        cpuPolicyLimitsMHz = mapOf("policy0" to 2_400L),
                        cpuPolicyHardwareMaxMHz = mapOf("policy0" to 2_800L),
                        psiCpuSomeAvg10 = 7.5,
                        psiMemorySomeAvg10 = 1.1,
                    )
                    val begin = SystemClock.elapsedRealtimeNanos()
                    assertTrue("Synthetic sample must persist", store.append(id, sample))
                    durations[i] = (SystemClock.elapsedRealtimeNanos() - begin)
                        .coerceAtLeast(0L) / 1_000_000L
                }
                val processCpuMs = Process.getElapsedCpuTime() - processCpuStart
                val pssAfter = Debug.getPss()
                assertEquals(80L, store.sampleCount(id))
                assertTrue(store.persistStop(id))
                assertTrue(store.finish(id))
                val sorted = durations.sorted()
                val median = sorted[sorted.size / 2]
                val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
                val slowest = sorted.last()
                val analysisStart = SystemClock.elapsedRealtimeNanos()
                val report = DukoSessionAnalyzer(app, name).analyze(id)
                val analysisMs = (SystemClock.elapsedRealtimeNanos() -
                    analysisStart).coerceAtLeast(0L) / 1_000_000L
                assertNotNull("Stored synthetic source must remain analyzable", report)
                assertEquals(id, report!!.sessionId)
                Log.i(tag, "Synthetic 80 writes: median=" + median +
                    " ms, p95=" + p95 + " ms, max=" + slowest +
                    " ms; source-analysis=" + analysisMs + " ms; " +
                    "process CPU-window=" + processCpuMs + " ms; " +
                    "PSS before/after KiB=" + pssBefore + "/" + pssAfter)
                // Only correctness and a generous hang guard are portable
                // across emulators. Actual overhead limits require Odin tests.
                assertTrue("A single test SQLite write must not hang for 60 seconds",
                    slowest < 60_000L)
            } finally {
                app.deleteDatabase(name)
            }
        }
}
