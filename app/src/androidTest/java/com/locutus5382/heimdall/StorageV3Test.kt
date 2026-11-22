package com.locutus5382.heimdall

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.AppendOutcome
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageV3Test {
    private fun sample(time: Long) = DeviceSample(
        time, 42.0, 76, 39.2, 1024L, 1900L, 680L,
        observedElapsedMs = time,
        thermalStatus = 2,
        thermalHeadroom = 0.45,
        cpuPolicyClocksMHz = mapOf("policy0" to 1400L)
    )

    @Test fun walStopTimestampAndInterruptedRecoveryDoNotEraseEvidence() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "heimdall_v3_wal_fixture.db"
        app.deleteDatabase(name)
        val store = LocalRecordingStore(app, name)

        val id = store.begin()
        val wallStart = System.currentTimeMillis()
        assertTrue(store.append(id, sample(wallStart)))
        assertTrue(store.persistStop(id))
        assertEquals(AppendOutcome.STOP_REQUESTED,
            store.appendOutcome(id, sample(wallStart + 5000)))
        assertTrue(store.finish(id))
        assertNull(store.unresolvedSessionId())

        val interrupted = store.begin()
        val timestamp = System.currentTimeMillis()
        assertTrue(store.append(interrupted, sample(timestamp)))
        assertEquals(interrupted, store.recoverInterruptedSession())
        assertEquals(null, store.unresolvedSessionId())
        assertEquals(1L, store.sampleCount(interrupted))

        SQLiteDatabase.openDatabase(
            app.getDatabasePath(name).absolutePath,
            null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            assertEquals(6, db.version)
            db.rawQuery("PRAGMA journal_mode", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("wal", c.getString(0).lowercase())
            }
            db.rawQuery(
                "SELECT end_reason, sample_count, ended_at_ms FROM sessions WHERE id=?",
                arrayOf(interrupted.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("INTERRUPTED", c.getString(0))
                assertEquals(1L, c.getLong(1))
                assertTrue(c.getLong(2) >= timestamp)
                assertTrue(c.getLong(2) <= System.currentTimeMillis())
            }
            db.rawQuery(
                "SELECT thermal_status, thermal_headroom, cpu_policy_clocks FROM samples WHERE session_id=?",
                arrayOf(id.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2, c.getInt(0))
                assertEquals(0.45, c.getDouble(1), .001)
                assertTrue(c.getString(2).contains("policy0=1400"))
            }
        }
    }
}
