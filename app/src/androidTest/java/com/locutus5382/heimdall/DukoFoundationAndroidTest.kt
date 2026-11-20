package com.locutus5382.heimdall

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoSessionContext
import com.locutus5382.heimdall.engine.duko.MeasurementOrigin
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.DukoSessionContextStore
import com.locutus5382.heimdall.recording.LocalRecordingStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DukoFoundationAndroidTest {
    @Test fun sessionCopiesUserTagsAndDeviceProfileAndCanBeEditedAfterRecording() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "duko_context_v4_test.db"
            val prefs = app.getSharedPreferences(
                "duko_next_session_v1", android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            app.deleteDatabase(name)
            try {
                val tags = DukoSessionContextStore(app, name)
                tags.saveDraft(DukoSessionContext(
                    game = "NFS Hot Pursuit", emulator = "Eden",
                    graphicsDriver = "Turnip 27 R2",
                    performanceMode = "High Performance",
                    fanProfile = "Sport",
                    clusterTuneEnabled = true,
                    clusterTuneProfile = "Medium",
                    tuningTool = "ClusterTune",
                    origin = MeasurementOrigin.USER_DECLARED
                ))
                val recorder = LocalRecordingStore(app, name)
                val session = recorder.begin()
                assertTrue(session > 0L)
                val snapshot = tags.get(session)
                assertEquals("Turnip 27 R2", snapshot.graphicsDriver)
                assertEquals("Medium", snapshot.clusterTuneProfile)
                assertEquals(true, snapshot.clusterTuneEnabled)
                assertEquals("Sport", snapshot.fanProfile)
                assertEquals(MeasurementOrigin.USER_DECLARED, snapshot.origin)

                val profile = JSONObject(requireNotNull(tags.deviceProfileJson(session)))
                assertEquals(1, profile.getInt("contractVersion"))
                assertTrue(profile.has("cpuPolicies"))
                assertTrue(profile.getString("manufacturer").isNotBlank())
                assertFalse(profile.has("batteryCapacityWh"))

                val base = 10_000L
                assertTrue(recorder.append(session, DeviceSample(
                    observedAtMs = System.currentTimeMillis(),
                    cpuLoadPercent = 40.0, batteryLevelPercent = 70,
                    batteryTemperatureC = 33.0, availableMemoryMiB = 1400L,
                    cpuClockMHz = 1600L, gpuClockMHz = null,
                    observedElapsedMs = base,
                    batteryCurrentUa = -2_000_000L,
                    batteryVoltageMv = 4_000,
                    batteryPlugged = false,
                    // Known electrical discharge is required for SUPPORTED energy.
                    batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING
                )))
                assertTrue(recorder.append(session, DeviceSample(
                    observedAtMs = System.currentTimeMillis() + 2_000L,
                    cpuLoadPercent = 60.0, batteryLevelPercent = 70,
                    batteryTemperatureC = 34.0, availableMemoryMiB = 1300L,
                    cpuClockMHz = 1800L, gpuClockMHz = null,
                    observedElapsedMs = base + 2_000L,
                    batteryCurrentUa = -2_000_000L,
                    batteryVoltageMv = 4_000,
                    batteryPlugged = false,
                    // Known electrical discharge is required for SUPPORTED energy.
                    batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING
                )))
                assertTrue(recorder.persistStop(session))
                assertTrue(recorder.finish(session))
                val report = requireNotNull(DukoSessionAnalyzer(app, name).analyze(session))
                assertEquals(2_000L, report.window.durationMs)
                val cpu = requireNotNull(report.integrity.metrics[DukoMetric.CPU_LOAD])
                assertEquals(50.0, cpu.timeWeightedMean!!, 1e-8)
                assertEquals(1.0, cpu.coverage, 1e-9)
                assertEquals(DukoEvidence.SUPPORTED, cpu.evidence)
                val gpu = requireNotNull(report.integrity.metrics[DukoMetric.GPU_LOAD])
                assertEquals(DukoEvidence.INSUFFICIENT, gpu.evidence)
                assertNull(gpu.timeWeightedMean)
                // SQLite adapter must preserve battery microamp and millivolt
                // units. 2A × 4V = 8W battery-terminal discharge.
                assertEquals(8.0,
                    requireNotNull(report.energy.timeWeightedDischargeWatts), 1e-9)
                assertEquals(8.0 * 2.0 / 3_600.0,
                    requireNotNull(report.energy.measuredDischargeWh), 1e-9)
                assertEquals(DukoEvidence.SUPPORTED, report.energy.evidence)

                tags.saveDraft(DukoSessionContext(
                    graphicsDriver = "T30",
                    clusterTuneProfile = "Large"
                ))
                assertEquals("Turnip 27 R2", tags.get(session).graphicsDriver)
                tags.save(session, snapshot.copy(
                    graphicsDriver = "Gen8 V39", fanProfile = "Quiet"))
                assertEquals("Gen8 V39", tags.get(session).graphicsDriver)
                assertEquals("Quiet", tags.get(session).fanProfile)
                assertEquals(2L, recorder.sampleCount(session))
            } finally {
                prefs.edit().clear().commit()
            }
        }

    @Test fun upgradingV3CreatesNewTablesWithoutModifyingHistoricalRows() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "duko_legacy_v3_test.db"
            app.deleteDatabase(name)
            val file = app.getDatabasePath(name)
            checkNotNull(file.parentFile).mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL(
                    """CREATE TABLE sessions (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       started_at_ms INTEGER NOT NULL,
                       status TEXT NOT NULL, ended_at_ms INTEGER,
                       stop_requested_at_ms INTEGER,
                       sample_count INTEGER NOT NULL DEFAULT 0
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE samples (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       session_id INTEGER, time_ms INTEGER, elapsed_ms INTEGER
                    )""".trimIndent()
                )
                db.insertOrThrow("sessions", null, ContentValues().apply {
                    put("id", 41L)
                    put("started_at_ms", 1_000L)
                    put("ended_at_ms", 4_000L)
                    put("status", "COMPLETED")
                    put("sample_count", 1L)
                })
                db.insertOrThrow("samples", null, ContentValues().apply {
                    put("session_id", 41L)
                    put("time_ms", 2_000L)
                    put("elapsed_ms", 2_000L)
                })
                db.setVersion(3)
            }
            val tags = DukoSessionContextStore(app, name)
            // This read opens the helper and triggers additive v3→v6 migration.
            assertNull(tags.deviceProfileJson(41L))
            SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                assertEquals(6, db.version)
                db.rawQuery("SELECT COUNT(*) FROM samples WHERE session_id=41", null)
                    .use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals(1L, c.getLong(0))
                    }
                db.rawQuery("SELECT COUNT(*) FROM sessions WHERE id=41", null)
                    .use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals(1L, c.getLong(0))
                    }
            }
            tags.save(41L, DukoSessionContext(graphicsDriver = "Original"))
            assertEquals("Original", tags.get(41L).graphicsDriver)
        }
}
