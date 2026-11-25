package com.locutus5382.heimdall

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallRecorderService
import com.locutus5382.heimdall.recording.RecorderStatusHub
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import com.locutus5382.heimdall.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run on a disposable Android emulator, not the user's Odin.
 * Exercise the actual Compose START/STOP controls, foreground service and SQLite.
 */
@RunWith(AndroidJUnit4::class)
class RecordingRoundTripTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun <T> await(stage: String, timeoutMs: Long, body: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            body()?.let { return it }
            Thread.sleep(100L)
        }
        throw AssertionError("Heimdall round-trip timed out at $stage; recorder=" +
            RecorderStatusHub.state.value.toString())
    }

    @Test
    fun dashboardStartsPersistsStopsAndFinalizesExactlyOneSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)

        val dbFile = app.getDatabasePath("heimdall_v1.db")
        // App-launch inspection may initialize the fresh empty SQLite DB before
        // this test begins. The final exact-one-row assertion guards freshness.

        // The previous test called the Service directly and missed broken UI wiring.
        compose.onNodeWithText("START").performClick()
        val recording = await("initial persisted recording", 30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Recording)
                ?.takeIf { it.persistedSamples > 0L }
        }
        val originalId = recording.sessionId
        assertTrue(originalId > 0L)
        compose.waitForIdle()
        compose.onNodeWithText("● RECORDING").assertExists()

        // An Activity recreation must reflect the original ongoing recording,
        // not allocate a second Service/engine or silently stop sampling.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("● RECORDING").assertExists()
        val afterRecreate = RecorderStatusHub.state.value as RecorderStatus.Recording
        assertEquals(originalId, afterRecreate.sessionId)

        // Even a second command from another control must not open another writer.
        app.startForegroundService(
            Intent(app, HeimdallRecorderService::class.java)
                .setAction(HeimdallRecorderService.START))
        compose.waitForIdle()
        compose.onNodeWithText("START").assertIsNotEnabled()

        // STOP must also originate from the dashboard, not a direct Service call.
        compose.onNodeWithText("STOP & SAVE").performClick()
        val finished = await("original session completion", 30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Finished)
                ?.takeIf { it.sessionId == originalId }
        }
        assertTrue(finished.samples > 0L)
        compose.waitForIdle()
        compose.onNodeWithText("● SAVED").assertExists()

        // Verify prototype history and CSV use the same persisted source rows.
        val archive = SessionArchive(app)
        val savedSessions = runBlocking { archive.recent() }
        assertEquals(1, savedSessions.size)
        assertEquals(originalId, savedSessions.single().id)
        assertEquals(finished.samples, savedSessions.single().samples)
        val bytes = ByteArrayOutputStream()
        val csvRows = runBlocking { archive.exportCsv(originalId, bytes) }
        assertEquals(finished.samples, csvRows)
        val overview = runBlocking { archive.overview(originalId) }
        assertEquals(csvRows, overview.totalSamples)
        if (overview.cpuLoad != null) {
            assertTrue(overview.cpuLoad!!.median <= overview.cpuLoad!!.p95)
            assertTrue(overview.cpuLoad!!.p95 <= overview.cpuLoad!!.peak)
        }
        val csv = bytes.toString("UTF-8")
        assertTrue(csv.startsWith("sample_id,time_ms,cpu_load_pct"))
        assertEquals(csvRows.toInt() + 1, csv.lines().filter { it.isNotEmpty() }.size)

        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        database.use { db ->
            db.rawQuery(
                "SELECT COUNT(*), MIN(status), MAX(status), MIN(ended_at_ms) FROM sessions",
                null
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals("COMPLETED", c.getString(1))
                assertEquals("COMPLETED", c.getString(2))
                assertFalse(c.isNull(3))
            }
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(8, cursor.getInt(0))
            }
            db.rawQuery(
                "SELECT stop_requested_at_ms FROM sessions WHERE id=?",
                arrayOf(originalId.toString())
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse("STOP must be durable inside SQLite", cursor.isNull(0))
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM samples WHERE session_id=?",
                arrayOf(originalId.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getLong(0) > 0L)
            }
        }

        // Repeated recordings are an important real-world failure mode.
        // A prior completed row must be immutable when another session starts.
        compose.onNodeWithText("START").performClick()
        val next = await("second persisted recording", 30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Recording)
                ?.takeIf { it.sessionId != originalId && it.persistedSamples > 0L }
        }
        assertTrue(next.sessionId > originalId)
        compose.onNodeWithText("STOP & SAVE").performClick()
        val second = await("second session completion", 30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Finished)
                ?.takeIf { it.sessionId == next.sessionId }
        }
        assertTrue(second.samples > 0L)
        val both = runBlocking { archive.recent() }
        assertEquals(2, both.size)
        assertEquals(next.sessionId, both[0].id)
        assertEquals(originalId, both[1].id)

        // Emulate process death between writing a session and receiving STOP.
        // Opening the dashboard must NOT silently allocate another row.
        val strandedId = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.insertOrThrow("sessions", null, android.content.ContentValues().apply {
                put("started_at_ms", System.currentTimeMillis() - 10_000L)
                put("status", "RECORDING")
            })
        }
        app.stopService(Intent(app, HeimdallRecorderService::class.java))
        Thread.sleep(200)
        compose.activityRule.scenario.recreate()
        val interrupted = await("orphaned session recovery", 30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Finished)
                ?.takeIf { it.sessionId == strandedId &&
                    it.reason == "INTERRUPTED" }
        }
        assertEquals(0L, interrupted.samples)
        compose.onNodeWithText("START").assertExists()

        // Emulate death after durable STOP but before finalization:
        // inspection must finish the SAME row automatically.
        val stoppingId = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.insertOrThrow("sessions", null, android.content.ContentValues().apply {
                put("started_at_ms", System.currentTimeMillis() - 10_000L)
                put("stop_requested_at_ms", System.currentTimeMillis() - 1_000L)
                put("status", "RECORDING")
            })
        }
        app.stopService(Intent(app, HeimdallRecorderService::class.java))
        Thread.sleep(200)
        compose.activityRule.scenario.recreate()
        await("durable-stop recovery", 30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Finished)
                ?.takeIf { it.sessionId == stoppingId }
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM sessions WHERE status='RECORDING'", null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            database.rawQuery(
                "SELECT COUNT(*) FROM sessions", null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4L, cursor.getLong(0))
            }
        }
    }
}
