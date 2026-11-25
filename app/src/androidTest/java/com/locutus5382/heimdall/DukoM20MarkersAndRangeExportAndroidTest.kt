package com.locutus5382.heimdall

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoSessionMarkerStore
import com.locutus5382.heimdall.recording.LocalRecordingStore
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DukoM20MarkersAndRangeExportAndroidTest {
    @Test fun liveAndSavedMarkersDoNotChangeSamplesAndSelectedCsvKeepsOriginalClocks() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "m20_marker_export.db"
            app.deleteDatabase(name)
            val store = LocalRecordingStore(app, name)
            val markers = DukoSessionMarkerStore(app, name)
            val archive = SessionArchive(app, name)
            val id = store.begin()
            val liveAt = SystemClock.elapsedRealtime()
            val live = markers.add(id, liveAt, "  Live fight  ", live = true)
            assertEquals("Live fight", live.label)
            val start = liveAt + 2_000L
            val now = System.currentTimeMillis()
            for (i in 0..5) {
                assertTrue(store.append(id, DeviceSample(
                    observedAtMs = now + i * 2_000L,
                    observedElapsedMs = start + i * 2_000L,
                    cpuLoadPercent = 20.0 + i,
                    batteryLevelPercent = 85,
                    batteryTemperatureC = 35.0,
                    availableMemoryMiB = null,
                    cpuClockMHz = null,
                    gpuClockMHz = null,
                )))
            }
            assertTrue(store.persistStop(id))
            assertTrue(store.finish(id))
            val second = markers.add(id, start + 4_000L,
                "Second scene, transition")
            assertEquals(2, markers.list(id).size)
            assertEquals(start + 4_000L, second.elapsedMs)
            assertTrue(markers.delete(id, live.id))
            assertEquals(listOf(second.id), markers.list(id).map { it.id })
            assertEquals(6L, store.sampleCount(id))
            assertTrue(runCatching {
                markers.add(id, start - 1L, "Outside recording")
            }.isFailure)
            assertTrue(runCatching {
                markers.add(id, start, "   ")
            }.isFailure)

            // Verify a text field with a comma is RFC4180-escaped.
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).absolutePath,
                null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE samples SET cpu_policy_clocks=? WHERE session_id=? AND elapsed_ms=?",
                    arrayOf("policy0=1000,policy3=1800", id, start + 4_000L)
                )
            }
            val full = ByteArrayOutputStream()
            assertEquals(6L, archive.exportCsv(id, full))
            val partial = ByteArrayOutputStream()
            val range = DukoWindow(start + 2_000L, start + 6_000L)
            assertEquals(3L, archive.exportCsv(id, partial, range))
            val csv = partial.toString("UTF-8")
            assertTrue(csv.startsWith("sample_id,time_ms,cpu_load_pct"))
            assertTrue(csv.contains((start + 2_000L).toString()))
            assertTrue(csv.contains((start + 6_000L).toString()))
            assertFalse(csv.contains((start + 8_000L).toString()))
            assertTrue(csv.contains("\"policy0=1000,policy3=1800\""))
            assertEquals(4, csv.trim().lines().size) // header + exactly three original rows
            assertEquals(6L, store.sampleCount(id))
            assertTrue(runCatching {
                archive.exportCsv(id, ByteArrayOutputStream(),
                    DukoWindow(start - 2_000L, start + 2_000L))
            }.isFailure)
        }

    @Test fun additiveV7ToV8MigrationRetainsOriginalRecordedRows() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "m20_v7_to_v8_marker.db"
        app.deleteDatabase(name)
        val file = app.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY, " +
                "status TEXT NOT NULL, started_elapsed_ms INTEGER)")
            db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY, " +
                "session_id INTEGER, elapsed_ms INTEGER, time_ms INTEGER)")
            db.execSQL("INSERT INTO sessions(id,status,started_elapsed_ms) " +
                "VALUES(71,'COMPLETED',1000)")
            db.execSQL("INSERT INTO samples(id,session_id,elapsed_ms,time_ms) " +
                "VALUES(44,71,2000,12345678)")
            db.version = 7
        }
        val markers = DukoSessionMarkerStore(app, name)
        val added = markers.add(71L, 2000L, "Post-run annotation")
        assertEquals(71L, added.sessionId)
        assertEquals(1, markers.list(71L).size)
        SQLiteDatabase.openDatabase(file.absolutePath,
            null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT id,elapsed_ms,time_ms FROM samples WHERE id=44",
                null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(44L, c.getLong(0))
                assertEquals(2000L, c.getLong(1))
                assertEquals(12345678L, c.getLong(2))
            }
        }
    }
}
