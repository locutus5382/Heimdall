package com.locutus5382.heimdall.recording

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.RecordingPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native v2 persistence. The recording row, durable STOP marker and samples
 * live in ONE SQLite database. There is no split SharedPreferences lease.
 *
 * v1 databases migrate in place without deleting a session or a sample.
 * Any unfinished row continues to block START until explicitly completed.
 */
internal class LocalRecordingStore(
    context: Context,
    private val databaseName: String = "heimdall_v1.db"
) : RecordingPersistence {
    private val app = context.applicationContext
    private val legacyLease = app.getSharedPreferences(
        "heimdall_recorder_lease_v1", Context.MODE_PRIVATE
    )

    private val db = object : SQLiteOpenHelper(app, databaseName, null, 2) {
        override fun onConfigure(database: SQLiteDatabase) {
            super.onConfigure(database)
            database.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at_ms INTEGER NOT NULL,
                    ended_at_ms INTEGER,
                    stop_requested_at_ms INTEGER,
                    status TEXT NOT NULL CHECK(status IN ('RECORDING','COMPLETED'))
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    time_ms INTEGER NOT NULL,
                    cpu_load_pct REAL,
                    battery_pct INTEGER,
                    battery_temp_c REAL,
                    free_memory_mib INTEGER,
                    cpu_clock_mhz INTEGER,
                    gpu_clock_mhz INTEGER,
                    FOREIGN KEY(session_id) REFERENCES sessions(id)
                )
            """.trimIndent())
            database.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, id)")
            database.execSQL("CREATE INDEX sessions_state_idx ON sessions(status, id)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) {
                throw IllegalStateException("UNSUPPORTED_MIGRATION_${oldVersion}_${newVersion}")
            }
            database.execSQL("ALTER TABLE sessions ADD COLUMN stop_requested_at_ms INTEGER")
            database.execSQL("CREATE INDEX sessions_state_idx ON sessions(status, id)")
            // Import only a *durably committed* v1 STOP. Never infer STOP from
            // a stale lease, a missing key, or a completed recording.
            if (legacyLease.getBoolean("active", false) &&
                legacyLease.getBoolean("stop_requested", false)) {
                val oldId = legacyLease.getLong("id", -1L)
                if (oldId > 0) {
                    database.execSQL(
                        """UPDATE sessions SET stop_requested_at_ms=?
                           WHERE id=? AND status='RECORDING'
                             AND stop_requested_at_ms IS NULL""",
                        arrayOf(System.currentTimeMillis(), oldId)
                    )
                }
            }
        }
    }

    /** Any open row, including a crashed v1 row, blocks a replacement START. */
    override suspend fun unresolvedSessionId(): Long? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id FROM sessions WHERE status='RECORDING' ORDER BY id LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    override suspend fun recoverPendingStop(): Long? = withContext(Dispatchers.IO) {
        // Do not silently auto-finish a different row when multiple unfinished
        // rows exist from an older faulty installation.
        val pending = db.readableDatabase.rawQuery(
            """SELECT id, stop_requested_at_ms FROM sessions
               WHERE status='RECORDING' ORDER BY id""", null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            val committed = !cursor.isNull(1)
            if (cursor.moveToNext() || !committed) null else id
        }
        if (pending != null && finish(pending)) pending else null
    }

    override suspend fun begin(): Long = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val existing = database.rawQuery(
                "SELECT id FROM sessions WHERE status='RECORDING' LIMIT 1", null
            ).use { it.moveToFirst() }
            check(!existing) { "UNFINISHED_SESSION" }
            val id = database.insertOrThrow("sessions", null, ContentValues().apply {
                put("started_at_ms", System.currentTimeMillis())
                put("status", "RECORDING")
            })
            check(id > 0L) { "SESSION_CREATE_FAILED" }
            database.setTransactionSuccessful()
            id
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun append(sessionId: Long, sample: DeviceSample): Boolean =
        withContext(Dispatchers.IO) {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                val writable = database.rawQuery(
                    """SELECT id FROM sessions WHERE id=? AND status='RECORDING'
                       AND stop_requested_at_ms IS NULL LIMIT 1""",
                    arrayOf(sessionId.toString())
                ).use { it.moveToFirst() }
                if (!writable) return@withContext false
                val row = ContentValues().apply {
                    put("session_id", sessionId)
                    put("time_ms", sample.observedAtMs)
                    sample.cpuLoadPercent?.let { put("cpu_load_pct", it) }
                    sample.batteryLevelPercent?.let { put("battery_pct", it) }
                    sample.batteryTemperatureC?.let { put("battery_temp_c", it) }
                    sample.availableMemoryMiB?.let { put("free_memory_mib", it) }
                    sample.cpuClockMHz?.let { put("cpu_clock_mhz", it) }
                    sample.gpuClockMHz?.let { put("gpu_clock_mhz", it) }
                }
                val saved = database.insertOrThrow("samples", null, row) > 0L
                if (saved) database.setTransactionSuccessful()
                saved
            } finally {
                database.endTransaction()
            }
        }

    override suspend fun persistStop(sessionId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                // Durable STOP belongs to exactly the same SQLite transaction
                // system as the session and its samples.
                val changed = database.update(
                    "sessions",
                    ContentValues().apply {
                        put("stop_requested_at_ms", System.currentTimeMillis())
                    },
                    "id=? AND status='RECORDING' AND stop_requested_at_ms IS NULL",
                    arrayOf(sessionId.toString())
                )
                val durable = changed == 1 || database.rawQuery(
                    """SELECT id FROM sessions WHERE id=? AND status='RECORDING'
                       AND stop_requested_at_ms IS NOT NULL LIMIT 1""",
                    arrayOf(sessionId.toString())
                ).use { it.moveToFirst() }
                if (durable) database.setTransactionSuccessful()
                durable
            } finally {
                database.endTransaction()
            }
        }

    override suspend fun finish(sessionId: Long): Boolean = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val status = database.rawQuery(
                "SELECT status, stop_requested_at_ms FROM sessions WHERE id=? LIMIT 1",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(0) to !cursor.isNull(1)
                else null
            }
            val completed = when {
                status == null -> false
                status.first == "COMPLETED" -> true // Preserve the original end time.
                status.first != "RECORDING" || !status.second -> false
                else -> database.update(
                    "sessions",
                    ContentValues().apply {
                        put("status", "COMPLETED")
                        put("ended_at_ms", System.currentTimeMillis())
                    },
                    "id=? AND status='RECORDING' AND stop_requested_at_ms IS NOT NULL",
                    arrayOf(sessionId.toString())
                ) == 1
            }
            if (completed) database.setTransactionSuccessful()
            completed
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun sampleCount(sessionId: Long): Long = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM samples WHERE session_id=?",
            arrayOf(sessionId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }
}
