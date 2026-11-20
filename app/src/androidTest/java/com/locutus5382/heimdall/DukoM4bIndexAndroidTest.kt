package com.locutus5382.heimdall

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.HeimdallDatabase
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** M4b: verify additive v5 -> v6 index migration and bounded large-session reads. */
@RunWith(AndroidJUnit4::class)
class DukoM4bIndexAndroidTest {
    @Test fun v5IndexMigrationPreservesAllHistoricalRowsAndSampleOrdering() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "duko_m4b_v5_index_fixture.db"
        app.deleteDatabase(name)
        val file = app.getDatabasePath(name)
        checkNotNull(file.parentFile).mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """CREATE TABLE sessions (
                    id INTEGER PRIMARY KEY,
                    started_at_ms INTEGER NOT NULL,
                    status TEXT NOT NULL
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE samples (
                    id INTEGER PRIMARY KEY,
                    session_id INTEGER NOT NULL,
                    time_ms INTEGER NOT NULL,
                    elapsed_ms INTEGER,
                    cpu_load_pct REAL,
                    battery_raw_current_ua INTEGER,
                    battery_raw_voltage_mv INTEGER
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id,id)")
            db.insertOrThrow("sessions", null, ContentValues().apply {
                put("id", 42L)
                put("started_at_ms", 1_000L)
                put("status", "COMPLETED")
            })
            // Deliberately out-of-order elapsed values: range index must not
            // reorder raw history or hide clock anomalies.
            listOf(4_000L, 2_000L, 6_000L).forEachIndexed { i, elapsed ->
                db.insertOrThrow("samples", null, ContentValues().apply {
                    put("id", 85L + i)
                    put("session_id", 42L)
                    put("time_ms", 100_000L + i)
                    put("elapsed_ms", elapsed)
                    put("cpu_load_pct", 30.0 + i)
                    put("battery_raw_current_ua", -1_200_000L - i)
                    put("battery_raw_voltage_mv", 3_900)
                })
            }
            db.version = 5
        }
        // Real onUpgrade() must create the new index without rewriting rows.
        assertNull(LocalRecordingStore(app, name).unresolvedSessionId())
        SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            assertEquals(6, db.version)
            val names = mutableSetOf<String>()
            db.rawQuery("PRAGMA index_list(samples)", null).use { c ->
                while (c.moveToNext()) names += c.getString(1)
            }
            assertTrue("samples_session_idx" in names)
            assertTrue("samples_session_elapsed_idx" in names)
            db.rawQuery(
                """SELECT id, elapsed_ms, cpu_load_pct,
                          battery_raw_current_ua, battery_raw_voltage_mv
                   FROM samples WHERE session_id=42 ORDER BY id""", null
            ).use { c ->
                assertEquals(3, c.count)
                listOf(4_000L, 2_000L, 6_000L).forEachIndexed { i, elapsed ->
                    assertTrue(c.moveToNext())
                    assertEquals(85L + i, c.getLong(0))
                    assertEquals(elapsed, c.getLong(1))
                    assertEquals(30.0 + i, c.getDouble(2), 1e-9)
                    assertEquals(-1_200_000L - i, c.getLong(3))
                    assertEquals(3_900, c.getInt(4))
                }
            }
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ok", c.getString(0))
            }
        }
    }

    @Test fun indexedWindowReadsSmallSliceOfLongSessionWithoutChangingStorage() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "duko_m4b_long_session.db"
            app.deleteDatabase(name)
            val store = LocalRecordingStore(app, name)
            val session = store.begin()
            val start = SystemClock.elapsedRealtime()
            val count = 12_000 // six hours and forty minutes at 2s cadence
            val db = HeimdallDatabase.helper(app, name).writableDatabase
            // A single batch transaction avoids test-runner scheduling noise;
            // actual recorder uses its existing durable per-sample protocol.
            db.beginTransaction()
            try {
                db.compileStatement(
                    """INSERT INTO samples (
                        session_id,time_ms,elapsed_ms,cpu_load_pct,
                        battery_pct,battery_temp_c,battery_current_ua,
                        battery_voltage_mv,battery_plugged,battery_status
                       ) VALUES (?,?,?,?,?,?,?,?,?,?)"""
                ).use { insert ->
                    for (i in 0 until count) {
                        insert.clearBindings()
                        insert.bindLong(1, session)
                        insert.bindLong(2, 100_000L + i)
                        insert.bindLong(3, start + i * 2_000L)
                        insert.bindDouble(4, 40.0)
                        insert.bindLong(5, 75L)
                        insert.bindDouble(6, 33.0)
                        insert.bindLong(7, -2_000_000L)
                        insert.bindLong(8, 4_000L)
                        insert.bindLong(9, 0L)
                        insert.bindLong(
                            10, BatteryManager.BATTERY_STATUS_DISCHARGING.toLong()
                        )
                        assertTrue(insert.executeInsert() > 0L)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            assertTrue(store.persistStop(session))
            assertTrue(store.finish(session))
            val firstTick = 6_000L
            val lastTick = 6_040L
            val window = DukoWindow(
                start + firstTick * 2_000L,
                start + lastTick * 2_000L,
            )
            val plan = mutableListOf<String>()
            db.rawQuery(
                """EXPLAIN QUERY PLAN SELECT elapsed_ms,cpu_load_pct
                   FROM samples INDEXED BY samples_session_elapsed_idx
                   WHERE session_id=? AND elapsed_ms>=? AND elapsed_ms<=?
                   ORDER BY id""",
                arrayOf(session.toString(), window.startMs.toString(),
                    window.endMs.toString())
            ).use { c ->
                while (c.moveToNext()) plan += c.getString(3)
            }
            assertTrue(
                "Expected the bounded time index in query plan: $plan",
                plan.any { "samples_session_elapsed_idx" in it }
            )
            val report = requireNotNull(
                DukoSessionAnalyzer(app, name).analyze(session, window)
            )
            val cpu = report.integrity.metrics.getValue(DukoMetric.CPU_LOAD)
            assertEquals(41, cpu.validSamples)
            assertEquals(80_000L, cpu.coveredMs)
            assertEquals(1.0, cpu.coverage, 1e-9)
            assertEquals(40.0, cpu.timeWeightedMean!!, 1e-9)
            assertEquals(8.0, report.energy.timeWeightedDischargeWatts!!, 1e-9)
            assertEquals(12_000L, store.sampleCount(session))
            assertEquals(window, report.window)
        }
}
