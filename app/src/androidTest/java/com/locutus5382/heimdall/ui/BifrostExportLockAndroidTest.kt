package com.locutus5382.heimdall.ui

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoComparisonQuality
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionOverview
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: a SAF result must synchronously lock the export action.
 * Otherwise a second tap can overwrite the destination/selected run before
 * its writer coroutine has started.
 */
@RunWith(AndroidJUnit4::class)
class BifrostExportLockAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(BifrostTestActivity::class.java)
    private val rows = listOf(
        SavedSession(41L, 1000L, 11000L, 6L),
        SavedSession(42L, 2000L, 12000L, 6L)
    )

    private fun await(name: String, ready: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 5000L
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (ready()) return
            Thread.sleep(25L)
        }
        throw AssertionError("Timed out waiting for $name")
    }

    @Test fun sessionExportCannotOpenSecondPickerWhileWriterStarts() {
        val source = object : SessionScreenDataSource {
            override suspend fun recent() = rows
            override suspend fun details(id: Long) =
                SessionDetail(SessionOverview(null, null, null, null, null, 6L), null, null)
            override suspend fun exportCsv(id: Long, uri: Uri): Long {
                delay(300)
                return 6L
            }
        }
        lateinit var model: SessionScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            model.refresh(0L)
        }
        await("saved sessions") { model.ui.value.history.size == 2 }
        activity.scenario.onActivity {
            assertTrue(model.requestExport(41L))
            model.onExportResult(Uri.parse("content://heimdall.test/first"))
            assertTrue(model.ui.value.exporting)
            assertFalse("Second CSV picker must be blocked", model.requestExport(42L))
        }
        await("session export completion") { !model.ui.value.exporting }
        assertEquals("Session #41 exported: 6 samples.", model.ui.value.notice)
    }

    @Test fun comparisonExportCannotStartSecondWriterUntilFirstCompletes() {
        val source = object : ComparisonDataSource {
            override suspend fun recent() = rows
            override suspend fun bounds(id: Long) = DukoWindow(0L, 10000L)
            override suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow) =
                DukoComparisonReport(a, b, DukoComparisonQuality.CAUTION,
                    listOf("USER_DECLARED"), emptyMap())
            override suspend fun write(uri: Uri, payload: String) {
                delay(300)
            }
        }
        lateinit var model: CompareScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, CompareScreenViewModel.Factory(source))
                .get(CompareScreenViewModel::class.java)
            model.refresh(0L)
        }
        await("saved A/B sessions") { model.ui.value.sessions.size == 2 }
        activity.scenario.onActivity {
            model.selectA(41L)
            model.selectB(42L)
            model.compare()
        }
        await("comparison report") { model.ui.value.report != null }
        activity.scenario.onActivity {
            assertEquals("DUKO_AB_41_42.txt", model.prepareExport(ComparisonExport.TXT))
            model.onExportResult(ComparisonExport.TXT,
                Uri.parse("content://heimdall.test/ab-first"))
            assertTrue(model.ui.value.writing)
            assertNull("No parallel picker while saving", model.prepareExport(ComparisonExport.CSV))
        }
        await("A/B export completion") { !model.ui.value.writing }
        assertEquals("TXT report saved locally.", model.ui.value.exportStatus)
    }
}
