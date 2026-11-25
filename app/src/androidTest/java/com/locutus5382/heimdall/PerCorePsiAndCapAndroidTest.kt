package com.locutus5382.heimdall

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.duko.DukoSessionContext
import com.locutus5382.heimdall.recording.DukoSessionAnalyzer
import com.locutus5382.heimdall.recording.DukoSessionContextStore
import com.locutus5382.heimdall.recording.HeimdallDatabase
import com.locutus5382.heimdall.recording.LocalRecordingStore
import com.locutus5382.heimdall.recording.SessionArchive
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerCorePsiAndCapAndroidTest {
    @Test fun capturesPerCorePsiCapTimelineAndCorrectCsvColumns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "duko_per_core_psi_v7_fixture.db"
        context.deleteDatabase(dbName)
        val tags = DukoSessionContextStore(context, dbName)
        val preferences = context.getSharedPreferences(
            "duko_next_session_v1", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            tags.saveDraft(DukoSessionContext(
                game = "synthetic instrumentation workload",
                clusterTuneEnabled = true,
                clusterTuneProfile = "user preset",
            ))
            val store = LocalRecordingStore(context, dbName)
            val id = store.begin()
            val origin = 20_000L
            for (i in 0..60) {
                val sample = DeviceSample(
                    observedAtMs = 1_700_000_000_000L + 2_000L * i,
                    observedElapsedMs = origin + 2_000L * i,
                    cpuLoadPercent = 45.0 + i,
                    batteryLevelPercent = 70,
                    batteryTemperatureC = 35.0,
                    availableMemoryMiB = 1024L,
                    cpuClockMHz = 2200L,
                    gpuClockMHz = 500L,
                    cpuCoreLoadPercent = mapOf(0 to (80.0 + i % 5),
                        1 to (20.0 + i % 5)),
                    psiCpuSomeAvg10 = 8.0 + i % 5,
                    psiMemorySomeAvg10 = 2.0 + i % 5,
                    psiMemoryFullAvg10 = 0.5 + i * 0.1,
                    cpuPolicyClocksMHz = mapOf("policy0" to if (i < 30) 1600L else 1800L),
                    cpuPolicyLimitsMHz = mapOf("policy0" to 1800L),
                    cpuPolicyHardwareMaxMHz = mapOf("policy0" to 2100L),
                )
                assertTrue(store.append(id, sample))
            }
            assertTrue(store.persistStop(id))
            assertTrue(store.finish(id))
            val report = requireNotNull(DukoSessionAnalyzer(context, dbName).analyze(id))
            assertEquals(0, report.coreActivity?.busiestCore)
            assertEquals(61, report.perCoreTimelines[0]?.samples)
            assertEquals(8.0, report.timelines.getValue("CPU PSI SOME AVG10")
                .minimum!!, 0.01)
            assertEquals(origin, report.timelines.getValue("CPU PSI SOME AVG10")
                .minAtMs)
            assertEquals(12.0, report.timelines.getValue("CPU PSI SOME AVG10")
                .maximum!!, 0.01)
            assertEquals(61, report.timelines.getValue("MEMORY PSI FULL AVG10").samples)
            assertTrue(requireNotNull(report.frequencyCaps)
                .policies.single { it.policyId == "policy0" }.presetLike)
            assertEquals(true, report.declaredClusterTuneEnabled)
            val minutes = requireNotNull(report.minuteAnalysis)
            assertEquals(2L, minutes.totalBlocks)
            assertEquals(2, minutes.blocks.size)
            assertEquals(30, minutes.blocks[0].recordedRows)
            assertEquals(31, minutes.blocks[1].recordedRows)
            assertTrue(minutes.blocks[0].metrics.getValue(
                com.locutus5382.heimdall.engine.duko.DukoMetric.CPU_LOAD
            ).p95 != null)
            val cpuPolicy = report.cpuPolicyResidency.single {
                it.policyId == "policy0"
            }
            assertEquals(61, cpuPolicy.observedSamples)
            assertEquals(1, cpuPolicy.residency.transitions)
            assertEquals(120_000L, cpuPolicy.residency.coveredMs)
            assertTrue(requireNotNull(report.events).events.any {
                it.type == com.locutus5382.heimdall.engine.duko.DukoEventType.POLICY_CAP_OBSERVED
            })
            val csv = ByteArrayOutputStream()
            SessionArchive(context, dbName).exportCsv(id, csv)
            val text = csv.toString("UTF-8")
            assertTrue(text.contains("cpu_core_load_pct"))
            assertTrue(text.contains("psi_cpu_some_avg10"))
            assertTrue(text.contains("psi_memory_full_avg10"))
            assertTrue(text.contains("0=80.0|1=20.0"))
            // SQLite REAL columns may render whole numbers as "8" or "8.0".
            // Verify the actual named columns and numerical readings instead
            // of relying on a particular string representation.
            val csvRows = text.trimEnd().lines()
            val headers = csvRows.first().split(',')
            val first = csvRows[1].split(',')
            fun reading(name: String): Double {
                val position = headers.indexOf(name)
                assertTrue("Missing CSV column $name", position >= 0)
                return first[position].toDouble()
            }
            assertEquals(8.0, reading("psi_cpu_some_avg10"), 1e-6)
            assertEquals(2.0, reading("psi_memory_some_avg10"), 1e-6)
            assertEquals(0.5, reading("psi_memory_full_avg10"), 1e-6)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun additiveV6ToV7MigrationPreservesHistoricalRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "heimdall_v6_to_v7_core_psi_fixture.db"
        context.deleteDatabase(dbName)
        val file = context.getDatabasePath(dbName)
        checkNotNull(file.parentFile).mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY, started_at_ms INTEGER NOT NULL," +
                "status TEXT NOT NULL)")
            db.execSQL("CREATE TABLE samples (" +
                "id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL," +
                "time_ms INTEGER NOT NULL,elapsed_ms INTEGER,cpu_load_pct REAL)")
            db.insertOrThrow("sessions", null, ContentValues().apply {
                put("id", 42L); put("started_at_ms", 1000L)
                put("status", "COMPLETED")
            })
            db.insertOrThrow("samples", null, ContentValues().apply {
                put("id", 11L); put("session_id", 42L)
                put("time_ms", 2000L); put("cpu_load_pct", 64.0)
            })
            db.setVersion(6)
        }
        val database = HeimdallDatabase.helper(context, dbName).readableDatabase
        assertEquals(7, database.version)
        database.rawQuery("SELECT id, session_id, cpu_load_pct, cpu_core_load_pct," +
            " psi_cpu_some_avg10,psi_memory_some_avg10,psi_memory_full_avg10 " +
            "FROM samples WHERE id=11", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(11L, cursor.getLong(0))
            assertEquals(42L, cursor.getLong(1))
            assertEquals(64.0, cursor.getDouble(2), 1e-7)
            for (column in 3..6) assertTrue(cursor.isNull(column))
        }
    }
}
