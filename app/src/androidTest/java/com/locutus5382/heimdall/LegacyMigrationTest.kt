package com.locutus5382.heimdall

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real SQLite v1 file is migrated in place, never by wiping the app. */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationTest {
    @Test fun preservesOldSamplesAndReplaysOnlyCommittedStop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "heimdall_v1_migration_fixture.db"
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        checkNotNull(file.parentFile).mkdirs()
        val lease = context.getSharedPreferences(
            "heimdall_recorder_lease_v1", Context.MODE_PRIVATE)
        lease.edit().clear().commit()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("""
                    CREATE TABLE sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        started_at_ms INTEGER NOT NULL,
                        ended_at_ms INTEGER,
                        status TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id INTEGER NOT NULL,
                        time_ms INTEGER NOT NULL,
                        cpu_load_pct REAL,
                        battery_pct INTEGER,
                        battery_temp_c REAL,
                        free_memory_mib INTEGER,
                        cpu_clock_mhz INTEGER,
                        gpu_clock_mhz INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, id)")
                db.insertOrThrow("sessions", null, ContentValues().apply {
                    put("id", 42L)
                    put("started_at_ms", 123456789L)
                    put("status", "RECORDING")
                })
                db.insertOrThrow("samples", null, ContentValues().apply {
                    put("session_id", 42L)
                    put("time_ms", 123456790L)
                    put("battery_pct", 78)
                })
                db.setVersion(1)
            }
            assertTrue(lease.edit().putBoolean("active", true)
                .putLong("id", 42L).putBoolean("stop_requested", true).commit())
            val store = LocalRecordingStore(context, dbName)
            assertEquals(42L, store.unresolvedSessionId())
            assertEquals(42L, store.recoverPendingStop())
            assertNull(store.unresolvedSessionId())
            assertEquals(1L, store.sampleCount(42L))

            SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(2, db.version)
                db.rawQuery(
                    "SELECT status, started_at_ms, ended_at_ms, stop_requested_at_ms FROM sessions WHERE id=42",
                    null
                ).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("COMPLETED", c.getString(0))
                    assertEquals(123456789L, c.getLong(1))
                    assertFalse(c.isNull(2))
                    assertFalse(c.isNull(3))
                }
                db.rawQuery("SELECT battery_pct FROM samples WHERE session_id=42",
                    null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(78, c.getInt(0))
                }
            }
        } finally {
            lease.edit().clear().commit()
        }
    }

    @Test fun oldUnstoppedRecordingCannotBeReplacedOrAutoFinalized() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "heimdall_v1_unstopped_fixture.db"
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        checkNotNull(file.parentFile).mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at_ms INTEGER NOT NULL, ended_at_ms INTEGER, status TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE samples (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, cpu_load_pct REAL, battery_pct INTEGER, battery_temp_c REAL, free_memory_mib INTEGER, cpu_clock_mhz INTEGER, gpu_clock_mhz INTEGER)"
            )
            db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, id)")
            db.insertOrThrow("sessions", null, ContentValues().apply {
                put("id", 73L)
                put("started_at_ms", 42L)
                put("status", "RECORDING")
            })
            db.setVersion(1)
        }
        val store = LocalRecordingStore(context, dbName)
        assertEquals(73L, store.unresolvedSessionId())
        assertNull(store.recoverPendingStop())
        try {
            store.begin()
            fail("BEGIN must refuse old unfinished session")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("UNFINISHED_SESSION") == true)
        }
        assertTrue(store.persistStop(73L))
        assertTrue(store.finish(73L))
        assertNull(store.unresolvedSessionId())
    }
}
