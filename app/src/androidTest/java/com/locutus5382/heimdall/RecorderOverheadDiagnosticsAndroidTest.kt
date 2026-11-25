package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.recording.RecorderOverheadHub
import com.locutus5382.heimdall.recording.RuntimeDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only Diagnostics uses the exact same bounded observer as the Service. */
@RunWith(AndroidJUnit4::class)
class RecorderOverheadDiagnosticsAndroidTest {
    @Test fun explicitUserInspectionShowsTraceAndDoesNotInventGameMetrics() = runBlocking {
        val monitor = RecorderOverheadHub.monitor
        monitor.clear()
        try {
            monitor.begin(73L, 2_000L)
            monitor.tick(73L, 7L, 15L, 22L, 0L, 0L,
                processCpuDeltaMs = 3L)
            monitor.end(73L)
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val report = RuntimeDiagnostics(app).capture()
            val fields = report.lines.toMap()
            assertEquals("73", fields["Recorder trace session"])
            assertEquals("1", fields["Persisted ticks observed"])
            assertEquals("0", fields["Missed deadlines observed"])
            assertEquals("22", fields["Longest end-to-end tick ms"])
            assertEquals("3",
                fields["Process CPU consumed within observed tick windows ms"])
            assertTrue(fields["CPU attribution caveat"].orEmpty()
                .contains("NOT isolated recorder CPU"))
            assertTrue(report.asText().contains("K / overhead trace"))
            assertFalse(report.asText().contains("measured FPS"))
        } finally {
            monitor.clear()
        }
    }

    @Test fun absentOverheadRemainsUnknownNotZero() = runBlocking {
        RecorderOverheadHub.monitor.clear()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val report = RuntimeDiagnostics(app).capture().lines.toMap()
        assertTrue(report["K / overhead trace"].orEmpty().contains("unavailable"))
        assertFalse(report.containsKey("Recorder trace session"))
    }
}
