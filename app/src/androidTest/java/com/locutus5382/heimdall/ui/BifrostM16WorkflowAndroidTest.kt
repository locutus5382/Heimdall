package com.locutus5382.heimdall.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionOverview
import com.locutus5382.heimdall.ui.navigation.BifrostTestActivity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1.6 integration gates: a newly completed recording can be opened without
 * selecting the wrong historical row, and rapid touches cannot duplicate
 * an expensive A/B computation. Uses in-memory fake archives only.
 */
@RunWith(AndroidJUnit4::class)
class BifrostM16WorkflowAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(BifrostTestActivity::class.java)

    private val old = SavedSession(41L, 1000L, 11000L, 6L)
    private val fresh = SavedSession(42L, 2000L, 12000L, 6L)

    private fun waitFor(stage: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(30L)
        }
        throw AssertionError("M1.6 timed out at $stage")
    }

    @Test fun stopReviewOpensTheExactNewSessionAfterReload() {
        val rows = AtomicReference(listOf(old))
        val detailRequests = AtomicInteger(0)
        val source = object : SessionScreenDataSource {
            override suspend fun recent() = rows.get()
            override suspend fun details(id: Long): SessionDetail {
                detailRequests.incrementAndGet()
                return SessionDetail(
                    SessionOverview(null, null, null, null, null, 6L), null, null
                )
            }
            override suspend fun exportCsv(id: Long, uri: Uri) = 6L
        }
        lateinit var model: SessionScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            model.refresh(0L)
        }
        waitFor("old history") { model.ui.value.history.map { it.id } == listOf(41L) }
        rows.set(listOf(fresh, old)) // Service finished while Sessions was not visible.
        activity.scenario.onActivity { model.focusSession(42L) }
        waitFor("new saved session details") {
            model.ui.value.expandedId == 42L && model.ui.value.detail != null &&
                !model.ui.value.reading
        }
        assertEquals(42L, model.ui.value.expandedId)
        assertEquals(2, model.ui.value.history.size)
        assertEquals(1, detailRequests.get())
        activity.scenario.recreate()
        activity.scenario.onActivity { host ->
            val restored = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            assertEquals(42L, restored.ui.value.expandedId)
            assertTrue(restored.ui.value.detail != null)
        }
    }

    @Test fun manualRefreshFindsNewQuickSettingsSession() {
        val rows = AtomicReference(listOf(old))
        val source = object : SessionScreenDataSource {
            override suspend fun recent() = rows.get()
            override suspend fun details(id: Long) =
                SessionDetail(SessionOverview(null, null, null, null, null, 6L), null, null)
            override suspend fun exportCsv(id: Long, uri: Uri) = 6L
        }
        lateinit var model: SessionScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            model.refresh(0L)
        }
        waitFor("cached history") { model.ui.value.history.size == 1 }
        rows.set(listOf(fresh, old))
        activity.scenario.onActivity { model.reload() }
        waitFor("manual reload") { model.ui.value.history.size == 2 }
        assertEquals(42L, model.ui.value.history.first().id)
    }

    @Test fun archivedSessionPagingPreservesExpandedRun() {
        val all = (60L downTo 1L).map { id ->
            SavedSession(id, id * 1000L, id * 1000L + 5000L, 6L)
        }
        val source = object : SessionScreenDataSource {
            override suspend fun recent() = all.take(20)
            override suspend fun recent(limit: Int) = all.take(limit)
            override suspend fun details(id: Long) = SessionDetail(
                SessionOverview(null, null, null, null, null, 6L), null, null
            )
            override suspend fun exportCsv(id: Long, uri: Uri) = 6L
        }
        lateinit var model: SessionScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, SessionScreenViewModel.Factory(source))
                .get(SessionScreenViewModel::class.java)
            model.refresh(0L)
        }
        waitFor("first page") { model.ui.value.history.size == 20 }
        assertTrue(model.ui.value.hasMoreHistory)
        activity.scenario.onActivity { model.toggle(60L) }
        waitFor("expanded newest") { model.ui.value.detail != null }
        activity.scenario.onActivity { model.loadOlder() }
        waitFor("second page") { model.ui.value.history.size == 40 }
        assertEquals(60L, model.ui.value.expandedId)
        assertTrue(model.ui.value.detail != null)
        activity.scenario.onActivity { model.loadOlder() }
        waitFor("last page") { model.ui.value.history.size == 60 }
        assertFalse(model.ui.value.hasMoreHistory)
    }

    @Test fun comparisonArchivePagingKeepsSelectedBaselineAndCandidate() {
        val all = (60L downTo 1L).map { id ->
            SavedSession(id, id * 1000L, id * 1000L + 5000L, 6L)
        }
        val source = object : ComparisonDataSource {
            override suspend fun recent() = all.take(20)
            override suspend fun recent(limit: Int) = all.take(limit)
            override suspend fun bounds(id: Long) = DukoWindow(0L, 5000L)
            override suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow):
                DukoComparisonReport? = null
            override suspend fun write(uri: Uri, payload: String) = Unit
        }
        lateinit var model: CompareScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, CompareScreenViewModel.Factory(source))
                .get(CompareScreenViewModel::class.java)
            model.refresh(0L)
        }
        waitFor("initial A/B page") { model.ui.value.sessions.size == 20 }
        activity.scenario.onActivity {
            model.selectA(60L)
            model.selectB(59L)
            model.loadOlder()
        }
        waitFor("older A/B page") { model.ui.value.sessions.size == 40 }
        assertEquals(60L, model.ui.value.a)
        assertEquals(59L, model.ui.value.b)
        assertTrue(model.ui.value.canCompare)
        activity.scenario.onActivity { model.loadOlder() }
        waitFor("all available A/B rows") { model.ui.value.sessions.size == 60 }
        assertFalse(model.ui.value.hasMoreSessions)
        assertEquals(60L, model.ui.value.a)
    }

    @Test fun olderSessionBaselineHandoffIsNotDroppedOnCompareEntry() {
        val all = (60L downTo 1L).map { id ->
            SavedSession(id, id * 1000L, id * 1000L + 5000L, 6L)
        }
        val source = object : ComparisonDataSource {
            override suspend fun recent() = all.take(20)
            override suspend fun recent(limit: Int) = all.take(limit)
            override suspend fun bounds(id: Long) = DukoWindow(0L, 5000L)
            override suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow):
                DukoComparisonReport? = null
            override suspend fun write(uri: Uri, payload: String) = Unit
        }
        lateinit var model: CompareScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, CompareScreenViewModel.Factory(source))
                .get(CompareScreenViewModel::class.java)
            model.refresh(0L)
        }
        waitFor("initial comparison history") { model.ui.value.sessions.size == 20 }
        activity.scenario.onActivity {
            model.selectBaselineFromSessions(5L) // not in newest twenty
        }
        waitFor("older linked baseline") {
            model.ui.value.sessions.size == 60 && model.ui.value.a == 5L &&
                !model.ui.value.loading
        }
        activity.scenario.onActivity { model.selectB(60L) }
        assertTrue(model.ui.value.canCompare)
        assertEquals(5L, model.ui.value.a)
        assertEquals(null, model.ui.value.issue)
    }

    @Test fun rapidCompareTouchesComputeOnceAndPreserveExistingSelection() {
        val compareCalls = AtomicInteger(0)
        val source = object : ComparisonDataSource {
            override suspend fun recent() = listOf(fresh, old)
            override suspend fun bounds(id: Long) = DukoWindow(0L, 10_000L)
            override suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow):
                DukoComparisonReport? {
                compareCalls.incrementAndGet()
                delay(200L)
                return null
            }
            override suspend fun write(uri: Uri, payload: String) = Unit
        }
        lateinit var model: CompareScreenViewModel
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host, CompareScreenViewModel.Factory(source))
                .get(CompareScreenViewModel::class.java)
            model.refresh(0L)
        }
        waitFor("A/B archive") { model.ui.value.sessions.size == 2 }
        activity.scenario.onActivity {
            model.selectA(41L)
            model.selectB(42L)
            assertTrue(model.ui.value.canCompare)
            model.compare()
            assertFalse(model.ui.value.canCompare)
            model.compare() // second touch is blocked synchronously
        }
        waitFor("single completed comparison") {
            compareCalls.get() == 1 && !model.ui.value.busy
        }
        assertEquals(1, compareCalls.get())
        activity.scenario.onActivity { model.selectA(41L) } // same selected id
        assertEquals(41L, model.ui.value.a)
        assertEquals(42L, model.ui.value.b)
    }
}
