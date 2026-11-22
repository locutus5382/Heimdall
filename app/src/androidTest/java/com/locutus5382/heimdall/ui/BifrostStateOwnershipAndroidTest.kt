package com.locutus5382.heimdall.ui

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionOverview
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real Activity ViewModelStore, but never create user recordings. */
@RunWith(AndroidJUnit4::class)
class BifrostStateOwnershipAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(BifrostTestActivity::class.java)

    private val rows = listOf(
        SavedSession(41L, 1000L, 11000L, 6L),
        SavedSession(42L, 2000L, 12000L, 6L)
    )

    private fun waitUntil(label: String, done: () -> Boolean) {
        val expiry = android.os.SystemClock.elapsedRealtime() + 5000L
        while (android.os.SystemClock.elapsedRealtime() < expiry) {
            if (done()) return
            Thread.sleep(30L)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    @Test fun compareSelectionsPersistThroughActivityRecreation() {
        val source = object : ComparisonDataSource {
            override suspend fun recent() = rows
            override suspend fun bounds(id: Long): DukoWindow? = DukoWindow(0L, 10000L)
            override suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow):
                DukoComparisonReport? = null
            override suspend fun write(uri: Uri, payload: String) = Unit
        }
        lateinit var original: CompareScreenViewModel
        activity.scenario.onActivity { host ->
            original = ViewModelProvider(host, CompareScreenViewModel.Factory(source))
                .get(CompareScreenViewModel::class.java)
            original.refresh(0L)
            original.selectA(41L)
            original.selectB(42L)
            original.cycleA()
            original.setStartB("8")
        }
        waitUntil("saved runs") { original.ui.value.sessions.size == 2 }
        activity.scenario.recreate()
        activity.scenario.onActivity { host ->
            val restored = ViewModelProvider(host, CompareScreenViewModel.Factory(source))
                .get(CompareScreenViewModel::class.java)
            assertSame(original, restored)
            assertEquals(41L, restored.ui.value.a)
            assertEquals(42L, restored.ui.value.b)
            assertEquals(AbWindowPreset.MIDDLE_80, restored.ui.value.presetA)
            assertEquals("8", restored.ui.value.startB)
        }
    }

    @Test fun pendingSessionExportUsesOriginalIdAfterRecreation() {
        var exportedId: Long? = null
        val source = object : SessionScreenDataSource {
            override suspend fun recent() = rows
            override suspend fun details(id: Long) = SessionDetail(
                SessionOverview(null, null, null, null, null, 6L), null, null
            )
            override suspend fun exportCsv(id: Long, uri: Uri): Long {
                exportedId = id
                return 6L
            }
        }
        lateinit var original: SessionScreenViewModel
        activity.scenario.onActivity { host ->
            original = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            original.refresh(0L)
        }
        waitUntil("saved runs") { original.ui.value.history.size == 2 }
        activity.scenario.onActivity { assertTrue(original.requestExport(41L)) }
        activity.scenario.recreate()
        activity.scenario.onActivity { host ->
            val restored = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            assertSame(original, restored)
            assertEquals(41L, restored.ui.value.pendingExportId)
            restored.onExportResult(Uri.parse("content://heimdall.test/session-41"))
        }
        waitUntil("CSV export") { exportedId == 41L }
        assertEquals(41L, exportedId)
        assertFalse(original.ui.value.exporting)
    }
}
