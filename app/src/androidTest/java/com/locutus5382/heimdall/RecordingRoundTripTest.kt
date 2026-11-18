package com.locutus5382.heimdall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.HeimdallRecorderService
import com.locutus5382.heimdall.recording.RecorderStatusHub
import com.locutus5382.heimdall.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses a fresh disposable API33 emulator, never the user's Odin installation.
 * Validates real Activity -> foreground Service -> SQLite -> STOP lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class RecordingRoundTripTest {
    private fun <T> await(timeoutMs: Long, body: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            body()?.let { return it }
            Thread.sleep(100L)
        }
        throw AssertionError("Heimdall native UI/service/SQLite round-trip timed out")
    }
    @Test fun newAppStartsPersistsAndFinalizesOneSession() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName,
                Manifest.permission.POST_NOTIFICATIONS)
        val dbFile = app.getDatabasePath("heimdall_v1.db")
        assertFalse("Test requires a clean ephemeral installation", dbFile.exists())
        ActivityScenario.launch(MainActivity::class.java).use {
            app.startForegroundService(
                Intent(app, HeimdallRecorderService::class.java)
                    .setAction(HeimdallRecorderService.START))
            val started = await(30_000L) {
                (RecorderStatusHub.state.value as? RecorderStatus.Recording)
                    ?.takeIf { it.persistedSamples > 0L }
            }
            val id = started.sessionId
            assertTrue(id > 0)
            // A repeated START must not create a second SQLite session.
            app.startForegroundService(
                Intent(app, HeimdallRecorderService::class.java)
                    .setAction(HeimdallRecorderService.START))
            app.startForegroundService(
                Intent(app, HeimdallRecorderService::class.java)
                    .setAction(HeimdallRecorderService.STOP))
            val saved = await(30_000L) {
                (RecorderStatusHub.state.value as? RecorderStatus.Finished)
                    ?.takeIf { it.sessionId == id }
            }
            assertTrue(saved.samples > 0)
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            db.use { database ->
                database.rawQuery(
                    "SELECT COUNT(*), MIN(status), MAX(status) FROM sessions", null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                    assertEquals("COMPLETED", cursor.getString(1))
                    assertEquals("COMPLETED", cursor.getString(2))
                }
                database.rawQuery(
                    "SELECT COUNT(*) FROM samples WHERE session_id=?",
                    arrayOf(id.toString())
                ).use { c ->
                    assertTrue(c.moveToFirst())
                    assertTrue(c.getLong(0) > 0L)
                }
            }
        }
    }
}
