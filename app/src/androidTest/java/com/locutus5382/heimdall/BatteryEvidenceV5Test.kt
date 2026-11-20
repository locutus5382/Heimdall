package com.locutus5382.heimdall

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.recording.BatteryEvidence
import com.locutus5382.heimdall.recording.HeimdallDatabase
import com.locutus5382.heimdall.recording.LocalRecordingStore
import com.locutus5382.heimdall.recording.ThermalHeadroomGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryEvidenceV5Test {
    @Test fun policyPreservesSignAndAcceptsPluggedDischargeWithoutInventingCurrent() {
        val discharging = BatteryManager.BATTERY_STATUS_DISCHARGING
        val charging = BatteryManager.BATTERY_STATUS_CHARGING
        val bypass = BatteryManager.BATTERY_STATUS_NOT_CHARGING
        assertEquals(-800_000L,
            BatteryEvidence.interpretedCurrentUa(-800_000L, discharging))
        assertNull(BatteryEvidence.interpretedCurrentUa(800_000L, discharging))
        assertEquals(750_000L,
            BatteryEvidence.interpretedCurrentUa(750_000L, charging))
        assertNull(BatteryEvidence.interpretedCurrentUa(-900_000L, bypass))
        assertNull(BatteryEvidence.interpretedCurrentUa(Long.MIN_VALUE, discharging))
        assertEquals(8_500, BatteryEvidence.acceptedVoltageMv(8_500))
        assertNull(BatteryEvidence.acceptedVoltageMv(18_000))
    }

    @Test fun thermalHeadroomIsMeasuredNoMoreOftenThanTenSeconds() {
        val gate = ThermalHeadroomGate()
        assertTrue(gate.shouldQuery(100_000L))
        assertFalse(gate.shouldQuery(105_000L))
        assertFalse(gate.shouldQuery(109_999L))
        assertTrue(gate.shouldQuery(110_000L))
        assertFalse(gate.shouldQuery(110_001L))
        assertTrue("Elapsed clock reset must not permanently silence headroom",
            gate.shouldQuery(0L))
    }

    @Test fun originalBatteryEvidenceIsDurableEvenWhenDischargeCannotBeInterpreted() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "heimdall_v5_raw_evidence_fixture.db"
            app.deleteDatabase(name)
            val store = LocalRecordingStore(app, name)
            val id = store.begin()
            val sample = DeviceSample(
                observedAtMs = System.currentTimeMillis(),
                cpuLoadPercent = null,
                batteryLevelPercent = 68,
                batteryTemperatureC = 36.1,
                availableMemoryMiB = null,
                cpuClockMHz = null,
                gpuClockMHz = null,
                observedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                batteryCurrentUa = null,
                batteryVoltageMv = 8500,
                batteryPlugged = true,
                batteryRawCurrentUa = 930_000L,
                batteryRawVoltageMv = 8500,
                batteryStatus = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                batteryChargeCounterUah = 4_550_000L,
                batteryEnergyCounterNwh = 31_000_000_000L,
                thermalHeadroom = 0.65,
                thermalHeadroomObservedElapsedMs = 1_234_567L,
            )
            assertTrue(store.append(id, sample))
            assertTrue(store.persistStop(id))
            assertTrue(store.finish(id))
            SQLiteDatabase.openDatabase(
                app.getDatabasePath(name).absolutePath, null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                assertEquals(6, db.version)
                db.rawQuery(
                    """SELECT battery_current_ua, battery_raw_current_ua,
                              battery_raw_voltage_mv, battery_status,
                              battery_charge_counter_uah, battery_energy_counter_nwh,
                              thermal_headroom_observed_elapsed_ms
                       FROM samples WHERE session_id=?""",
                    arrayOf(id.toString())
                ).use { c ->
                    assertTrue(c.moveToFirst())
                    assertTrue("Ambiguous current must not enter interpreted series",
                        c.isNull(0))
                    assertEquals(930_000L, c.getLong(1))
                    assertEquals(8500, c.getInt(2))
                    assertEquals(BatteryManager.BATTERY_STATUS_NOT_CHARGING, c.getInt(3))
                    assertEquals(4_550_000L, c.getLong(4))
                    assertEquals(31_000_000_000L, c.getLong(5))
                    assertEquals(1_234_567L, c.getLong(6))
                }
            }
        }

    @Test fun v4DatabaseMigratesThroughV5ToV6WithoutChangingOldValuesOrIds() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "heimdall_v4_to_v5_fixture.db"
        app.deleteDatabase(name)
        val dbFile = app.getDatabasePath(name)
        checkNotNull(dbFile.parentFile).mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("""
                CREATE TABLE sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at_ms INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    end_reason TEXT,
                    sample_count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    time_ms INTEGER NOT NULL,
                    elapsed_ms INTEGER,
                    battery_current_ua INTEGER,
                    battery_voltage_mv INTEGER,
                    battery_plugged INTEGER
                )
            """.trimIndent())
            db.insertOrThrow("sessions", null, ContentValues().apply {
                put("id", 42L)
                put("started_at_ms", 100_000L)
                put("status", "COMPLETED")
                put("sample_count", 1)
            })
            db.insertOrThrow("samples", null, ContentValues().apply {
                put("id", 85L)
                put("session_id", 42L)
                put("time_ms", 100_100L)
                put("battery_current_ua", -1_200_000L)
                put("battery_voltage_mv", 3900)
                put("battery_plugged", 0)
            })
            db.version = 4
        }
        // Force the real database helper's migration path, not a synthetic
        // hand-written ALTER TABLE.
        val store = LocalRecordingStore(app, name)
        assertNull(store.unresolvedSessionId())
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            assertEquals(6, db.version)
            db.rawQuery("""
                SELECT id, session_id, time_ms, battery_current_ua,
                       battery_voltage_mv, battery_raw_current_ua,
                       battery_status, battery_energy_counter_nwh
                  FROM samples WHERE id=85
            """.trimIndent(), null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(85L, c.getLong(0))
                assertEquals(42L, c.getLong(1))
                assertEquals(100_100L, c.getLong(2))
                assertEquals(-1_200_000L, c.getLong(3))
                assertEquals(3900, c.getInt(4))
                assertTrue("Earlier records must not be retroactively invented", c.isNull(5))
                assertTrue(c.isNull(6))
                assertTrue(c.isNull(7))
            }
            db.rawQuery("SELECT sample_count FROM sessions WHERE id=42",
                null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }
}
