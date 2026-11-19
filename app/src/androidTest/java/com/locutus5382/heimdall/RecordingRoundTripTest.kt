package com.locutus5382.heimdall

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallRecorderService
import com.locutus5382.heimdall.recording.RecorderStatusHub
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

    private fun <T> await(timeoutMs: Long, body: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            body()?.let { return it }
            Thread.sleep(100L)
        }
        throw AssertionError("Heimdall UI / service / SQLite round-trip timed out")
    }

    @Test
    fun dashboardStartsPersistsStopsAndFinalizesExactlyOneSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)

        val dbFile = app.getDatabasePath("heimdall_v1.db")
        assertFalse("Requires a fresh disposable emulator app installation", dbFile.exists())

        // The previous test called the Service directly and missed broken UI wiring.
        compose.onNodeWithText("START").performClick()
        val recording = await(30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Recording)
                ?.takeIf { it.persistedSamples > 0L }
        }
        val originalId = recording.sessionId
        assertTrue(originalId > 0L)
        compose.waitForIdle()
        compose.onNodeWithText("Recording telemetry").assertExists()

        // Even a second command from another control must not open another writer.
        app.startForegroundService(
            Intent(app, HeimdallRecorderService::class.java)
                .setAction(HeimdallRecorderService.START))
        compose.waitForIdle()
        compose.onNodeWithText("START").assertIsNotEnabled()

        // STOP must also originate from the dashboard, not a direct Service call.
        compose.onNodeWithText("STOP & SAVE").performClick()
        val finished = await(30_000L) {
            (RecorderStatusHub.state.value as? RecorderStatus.Finished)
                ?.takeIf { it.sessionId == originalId }
        }
        assertTrue(finished.samples > 0L)
        compose.waitForIdle()
        compose.onNodeWithText("Session saved").assertExists()

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
            db.rawQuery(
                "SELECT COUNT(*) FROM samples WHERE session_id=?",
                arrayOf(originalId.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getLong(0) > 0L)
            }
        }
    }
}
