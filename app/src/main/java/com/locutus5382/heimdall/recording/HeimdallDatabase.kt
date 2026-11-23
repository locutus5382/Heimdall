package com.locutus5382.heimdall.recording

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One helper per database name, shared by the recorder and read-only screens.
 * SQLite WAL permits exports/diagnostics to read a stable snapshot while the
 * foreground service appends samples. Never rename the existing on-disk file.
 */
internal object HeimdallDatabase {
    const val NAME = "heimdall_v1.db"
    // v7 adds nullable per-core/PSI telemetry; historical rows remain intact.
    private const val VERSION = 7
    private val instances = mutableMapOf<String, SQLiteOpenHelper>()

    @Synchronized
    fun helper(context: Context, filename: String = NAME): SQLiteOpenHelper {
        val app = context.applicationContext
        val key = app.packageName + ":" + filename
        return instances.getOrPut(key) {
            object : SQLiteOpenHelper(app, filename, null, VERSION) {
                init { setWriteAheadLoggingEnabled(true) }

                override fun onConfigure(db: SQLiteDatabase) {
                    super.onConfigure(db)
                    db.setForeignKeyConstraintsEnabled(true)
                    // Android SQLiteDatabase.execSQL forbids PRAGMA statements
                    // that return results. WAL is enabled through the helper
                    // API; rely on Android's connection-level busy handling.
                }

                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE sessions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            started_at_ms INTEGER NOT NULL,
                            started_elapsed_ms INTEGER,
                            ended_at_ms INTEGER,
                            stop_requested_at_ms INTEGER,
                            status TEXT NOT NULL
                              CHECK(status IN ('RECORDING','COMPLETED')),
                            end_reason TEXT,
                            sample_count INTEGER NOT NULL DEFAULT 0,
                            device_model TEXT,
                            android_sdk INTEGER,
                            app_version TEXT,
                            sample_interval_ms INTEGER NOT NULL DEFAULT 2000,
                            capability_summary TEXT
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE samples (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            session_id INTEGER NOT NULL,
                            time_ms INTEGER NOT NULL,
                            elapsed_ms INTEGER,
                            cpu_load_pct REAL,
                            battery_pct INTEGER,
                            battery_temp_c REAL,
                            free_memory_mib INTEGER,
                            cpu_clock_mhz INTEGER,
                            gpu_clock_mhz INTEGER,
                            thermal_status INTEGER,
                            thermal_headroom REAL,
                            battery_current_ua INTEGER,
                            battery_voltage_mv INTEGER,
                            battery_plugged INTEGER,
                            battery_raw_current_ua INTEGER,
                            battery_raw_voltage_mv INTEGER,
                            battery_status INTEGER,
                            battery_charge_counter_uah INTEGER,
                            battery_energy_counter_nwh INTEGER,
                            thermal_headroom_observed_elapsed_ms INTEGER,
                            gpu_load_pct REAL,
                            cpu_temp_c REAL,
                            gpu_temp_c REAL,
                            cpu_policy_clocks TEXT,
                            cpu_policy_limits TEXT,
                            cpu_policy_hardware_max TEXT,
                            cpu_core_load_pct TEXT,
                            psi_cpu_some_avg10 REAL,
                            psi_memory_some_avg10 REAL,
                            psi_memory_full_avg10 REAL,
                            FOREIGN KEY(session_id) REFERENCES sessions(id)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id,id)")
                    db.execSQL(
                        "CREATE INDEX samples_session_elapsed_idx " +
                            "ON samples(session_id,elapsed_ms,id)"
                    )
                    db.execSQL("CREATE INDEX sessions_state_idx ON sessions(status,id)")
                    createDukoTables(db)
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    require(oldVersion in 1..6 && newVersion == 7) {
                        "UNSUPPORTED_MIGRATION_${oldVersion}_${newVersion}"
                    }
                    if (oldVersion == 1) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN stop_requested_at_ms INTEGER")
                        db.execSQL("CREATE INDEX sessions_state_idx ON sessions(status,id)")
                        val legacy = app.getSharedPreferences(
                            "heimdall_recorder_lease_v1", Context.MODE_PRIVATE
                        )
                        if (legacy.getBoolean("active", false) &&
                            legacy.getBoolean("stop_requested", false)) {
                            val oldId = legacy.getLong("id", -1L)
                            if (oldId > 0L) {
                                db.execSQL(
                                    """UPDATE sessions SET stop_requested_at_ms=?
                                       WHERE id=? AND status='RECORDING'
                                       AND stop_requested_at_ms IS NULL""",
                                    arrayOf(System.currentTimeMillis(), oldId)
                                )
                            }
                        }
                    }
                    // The v3 columns already exist on version-3 installs.
                    // Versions 1/2 first acquire their full v3 contract.
                    if (oldVersion <= 2) {
                    listOf(
                        "started_elapsed_ms INTEGER",
                        "end_reason TEXT",
                        "sample_count INTEGER NOT NULL DEFAULT 0",
                        "device_model TEXT",
                        "android_sdk INTEGER",
                        "app_version TEXT",
                        "sample_interval_ms INTEGER NOT NULL DEFAULT 2000",
                        "capability_summary TEXT",
                    ).forEach { db.execSQL("ALTER TABLE sessions ADD COLUMN $it") }
                    listOf(
                        "elapsed_ms INTEGER",
                        "thermal_status INTEGER",
                        "thermal_headroom REAL",
                        "battery_current_ua INTEGER",
                        "battery_voltage_mv INTEGER",
                        "battery_plugged INTEGER",
                        "gpu_load_pct REAL",
                        "cpu_temp_c REAL",
                        "gpu_temp_c REAL",
                        "cpu_policy_clocks TEXT",
                        "cpu_policy_limits TEXT",
                        "cpu_policy_hardware_max TEXT",
                    ).forEach { db.execSQL("ALTER TABLE samples ADD COLUMN $it") }
                    db.execSQL(
                        """UPDATE sessions SET sample_count =
                           (SELECT COUNT(*) FROM samples WHERE session_id=sessions.id)
                           WHERE status='COMPLETED'"""
                    )
                    db.execSQL(
                        """UPDATE sessions SET end_reason='COMPLETED'
                           WHERE status='COMPLETED' AND end_reason IS NULL"""
                    )
                    }
                    // DUKO stores metadata in additive tables. Raw sessions
                    // and all prior samples remain untouched.
                    createDukoTables(db)
                    // v5: additive columns. Never rewrite original sample rows.
                    if (oldVersion <= 4) {
                        listOf(
                            "battery_raw_current_ua INTEGER",
                            "battery_raw_voltage_mv INTEGER",
                            "battery_status INTEGER",
                            "battery_charge_counter_uah INTEGER",
                            "battery_energy_counter_nwh INTEGER",
                            "thermal_headroom_observed_elapsed_ms INTEGER",
                        ).forEach { db.execSQL("ALTER TABLE samples ADD COLUMN $it") }
                    }
                    // v6: index only. Preserve all existing session IDs, rows,
                    // raw battery readings, timestamps and test context data.
                    // SQLiteOpenHelper runs onUpgrade inside a transaction.
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS samples_session_elapsed_idx " +
                            "ON samples(session_id,elapsed_ms,id)"
                    )
                    // v7: additive only. No rewrite of any historical row.
                    listOf(
                        "cpu_core_load_pct TEXT",
                        "psi_cpu_some_avg10 REAL",
                        "psi_memory_some_avg10 REAL",
                        "psi_memory_full_avg10 REAL"
                    ).forEach { db.execSQL("ALTER TABLE samples ADD COLUMN $it") }
                }
            }
        }
    }

    private fun createDukoTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS duko_session_contexts (
                session_id INTEGER PRIMARY KEY
                    REFERENCES sessions(id) ON DELETE CASCADE,
                game TEXT NOT NULL DEFAULT '',
                emulator TEXT NOT NULL DEFAULT '',
                graphics_driver TEXT NOT NULL DEFAULT '',
                performance_mode TEXT NOT NULL DEFAULT '',
                fan_profile TEXT NOT NULL DEFAULT '',
                cluster_tune_profile TEXT NOT NULL DEFAULT 'Unknown',
                cluster_tune_enabled INTEGER,
                tuning_tool TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                tag_source TEXT NOT NULL DEFAULT 'UNKNOWN',
                updated_at_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS duko_device_profiles (
                session_id INTEGER PRIMARY KEY
                    REFERENCES sessions(id) ON DELETE CASCADE,
                contract_version INTEGER NOT NULL,
                captured_at_ms INTEGER NOT NULL,
                profile_json TEXT NOT NULL
            )
        """.trimIndent())
    }
}
