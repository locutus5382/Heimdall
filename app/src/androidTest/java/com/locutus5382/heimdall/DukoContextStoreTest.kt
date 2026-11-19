package com.locutus5382.heimdall

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DukoSessionContext
import com.locutus5382.heimdall.engine.DukoSetting
import com.locutus5382.heimdall.engine.DukoSettingOrigin
import com.locutus5382.heimdall.engine.DukoUnderclockDeclaration
import com.locutus5382.heimdall.recording.DukoContextStore
import com.locutus5382.heimdall.recording.HeimdallDatabase
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DukoContextStoreTest {
    @Test fun sessionTuningTagsRoundTripWithoutChangingRecordedData() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "duko_test_tags_v4.db"
        app.deleteDatabase(filename)
        val recorder = LocalRecordingStore(app, filename)
        val sessionId = recorder.begin()
        val meta = DukoContextStore(app, filename)
        val original = meta.read(sessionId)
        assertNotNull(original)
        assertEquals(DukoUnderclockDeclaration.UNKNOWN, original!!.underclockUsed)
        val tagged = DukoSessionContext(
            sessionId = sessionId,
            game = "NFS Hot Pursuit",
            emulator = "Eden",
            emulatorVersion = "Test",
            graphicsDriver = "Turnip",
            graphicsDriverVersion = "27.0.0-R2",
            performanceMode = DukoSetting("High Performance",
                DukoSettingOrigin.USER_DECLARED),
            fanProfile = DukoSetting("Smart",
                DukoSettingOrigin.USER_DECLARED),
            underclockUsed = DukoUnderclockDeclaration.USED,
            tunerName = "ClusterTune",
            tunerProfile = DukoSetting("Medium Underclock",
                DukoSettingOrigin.USER_DECLARED),
            testProfile = "Fixed track",
            notes = "Cold start"
        )
        assertTrue(meta.save(tagged))
        assertEquals(tagged, meta.read(sessionId))
        assertNotNull(meta.wasClusterTuneInstalled(sessionId))
        assertNotNull(meta.readDeviceProfileJson(sessionId))
        assertEquals(0L, recorder.sampleCount(sessionId))
        assertTrue(recorder.persistStop(sessionId))
        assertTrue(recorder.finish(sessionId))
        assertEquals(tagged, meta.read(sessionId))
    }

    @Test fun v3MigrationPreservesSessionAndIntroducesEmptyContextTable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "duko_test_upgrade_v3.db"
        app.deleteDatabase(filename)
        val file = app.getDatabasePath(filename)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY, started_at_ms INTEGER, status TEXT)")
            old.execSQL("CREATE TABLE samples (" +
                "id INTEGER PRIMARY KEY, session_id INTEGER, time_ms INTEGER)")
            old.execSQL("INSERT INTO sessions VALUES (77,12345,'COMPLETED')")
            old.execSQL("INSERT INTO samples VALUES (8,77,12500)")
            old.version = 3
        }
        val current = HeimdallDatabase.helper(app, filename).writableDatabase
        assertEquals(4, current.version)
        current.rawQuery("SELECT id FROM sessions WHERE id=77", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(77L, it.getLong(0))
        }
        current.rawQuery("SELECT time_ms FROM samples WHERE id=8", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(12500L, it.getLong(0))
        }
        current.rawQuery("SELECT COUNT(*) FROM duko_session_meta", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }
}
