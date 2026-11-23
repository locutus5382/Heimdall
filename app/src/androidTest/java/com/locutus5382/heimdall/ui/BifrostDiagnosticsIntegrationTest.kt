package com.locutus5382.heimdall.ui

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.DiagnosticSnapshot
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Never performs an actual device probe or writes external files. */
@RunWith(AndroidJUnit4::class)
class BifrostDiagnosticsIntegrationTest {
    @get:Rule val activity = ActivityScenarioRule(BifrostTestActivity::class.java)

    private fun await(name: String, ready: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 5000L
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (ready()) return
            Thread.sleep(30L)
        }
        throw AssertionError("Timed out waiting for $name")
    }

    @Test fun diagnosticsAndFrozenExportSurviveActivityRecreation() {
        var exported = ""
        val source = object : DiagnosticsDataSource {
            override suspend fun capture() = DiagnosticSnapshot(
                1700000000000L, listOf("CPU policies discovered" to "3")
            )
            override suspend fun save(uri: Uri, text: String) {
                delay(150)
                exported = text
            }
        }
        lateinit var model: DiagnosticsViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, DiagnosticsViewModel.Factory(source))
                .get(DiagnosticsViewModel::class.java)
            model.inspect()
        }
        await("local capability capture") { model.ui.value.snapshot != null }
        activity.scenario.onActivity {
            assertEquals("Heimdall_device_diagnostics.txt", model.requestExport())
            assertNull("Duplicate picker must be prevented", model.requestExport())
        }
        activity.scenario.recreate()
        activity.scenario.onActivity { host ->
            val restored = ViewModelProvider(host, DiagnosticsViewModel.Factory(source))
                .get(DiagnosticsViewModel::class.java)
            assertSame(model, restored)
            assertEquals("3", restored.ui.value.snapshot?.lines?.first()?.second)
            restored.onExportResult(Uri.parse("content://heimdall.test/diagnostics"))
            assertTrue("Writer must lock synchronously", restored.ui.value.writing)
            assertNull(restored.requestExport())
        }
        await("local diagnostics export") { exported.isNotBlank() &&
            !model.ui.value.writing }
        assertTrue(exported.contains("CPU policies discovered: 3"))
        assertFalse(model.ui.value.writing)
        assertEquals("Diagnostic report saved locally.", model.ui.value.message)
    }

    @Test fun stalePickerCannotWriteUnownedFile() {
        var writes = 0
        val source = object : DiagnosticsDataSource {
            override suspend fun capture() =
                DiagnosticSnapshot(1L, emptyList())
            override suspend fun save(uri: Uri, text: String) { writes++ }
        }
        activity.scenario.onActivity { host ->
            val model = ViewModelProvider(host, DiagnosticsViewModel.Factory(source))
                .get(DiagnosticsViewModel::class.java)
            model.onExportResult(Uri.parse("content://heimdall.test/unowned"))
            assertEquals("Export request expired; no diagnostic file was written.",
                model.ui.value.message)
        }
        assertEquals(0, writes)
    }
}
