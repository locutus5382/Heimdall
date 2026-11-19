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
 * Brand-new Heimdall v1 schema, NOT a copied CoreMetrics DB or migration.
 * One session and one STOP owner. All DB and preference writes are local.
 */
internal class LocalRecordingStore(context: Context) : RecordingPersistence {
    private val app = context.applicationContext
    private val db = object : SQLiteOpenHelper(app, "heimdall_v1.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("""
                CREATE TABLE sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at_ms INTEGER NOT NULL,
                    ended_at_ms INTEGER,
                    status TEXT NOT NULL CHECK(status IN ('RECORDING','COMPLETED'))
                )
            """.trimIndent())
            d.execSQL("""
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
            d.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, id)")
        }
        override fun onUpgrade(
            db: SQLiteDatabase, oldVersion: Int, newVersion: Int
        ) {
            // Never drop tables/samples. New versions require explicit migration.
            throw IllegalStateException("MIGRATION_REQUIRED_${oldVersion}_${newVersion}")
        }
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
    private val lease = app.getSharedPreferences("heimdall_recorder_lease_v1",
        Context.MODE_PRIVATE)

    override suspend fun unresolvedSessionId(): Long? = withContext(Dispatchers.IO) {
        val row = db.readableDatabase.rawQuery(
            "SELECT id FROM sessions WHERE status='RECORDING' ORDER BY id LIMIT 1",
            null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val hasLease = lease.getBoolean("active", false)
        row ?: if (hasLease) lease.getLong("id", -1L) else null
    }

    /** Only a previously committed STOP is recoverable without user input. */
    override suspend fun recoverPendingStop(): Long? = withContext(Dispatchers.IO) {
        if (!lease.getBoolean("active", false) ||
            !lease.getBoolean("stop_requested", false)) return@withContext null
        val id = lease.getLong("id", -1L)
        if (id <= 0L) return@withContext null
        // If the database and durable lease disagree, preserve evidence rather
        // than announce a recovered session while a different row is open.
        if (unresolvedSessionId() != id) return@withContext null
        // finish() is retry-safe: COMPLETED timestamps are never rewritten.
        if (finish(id)) id else null
    }

    override suspend fun begin(): Long = withContext(Dispatchers.IO) {
        check(unresolvedSessionId() == null) { "UNFINISHED_SESSION" }
        val id = db.writableDatabase.insertOrThrow("sessions", null,
            ContentValues().apply {
                put("started_at_ms", System.currentTimeMillis())
                put("status", "RECORDING")
            })
        // One synchronous commit is the durability boundary. If it fails,
        // the original SQLite row remains unfinished; never open another.
        check(lease.edit().putBoolean("active", true).putLong("id", id)
            .putBoolean("stop_requested", false).commit()) {
                "LEASE_CREATE_FAILED"
            }
        id
    }

    override suspend fun append(
        sessionId: Long, sample: DeviceSample
    ): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("time_ms", sample.observedAtMs)
            sample.cpuLoadPercent?.let { put("cpu_load_pct", it) }
            sample.batteryLevelPercent?.let { put("battery_pct", it) }
            sample.batteryTemperatureC?.let { put("battery_temp_c", it) }
            sample.availableMemoryMiB?.let { put("free_memory_mib", it) }
            sample.cpuClockMHz?.let { put("cpu_clock_mhz", it) }
            sample.gpuClockMHz?.let { put("gpu_clock_mhz", it) }
        }
        if (!lease.getBoolean("active", false) ||
            lease.getLong("id", -1L) != sessionId ||
            lease.getBoolean("stop_requested", false)) return@withContext false
        db.writableDatabase.insertOrThrow("samples", null, values) > 0L
    }

    override suspend fun persistStop(sessionId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!lease.getBoolean("active", false) ||
                lease.getLong("id", -1L) != sessionId) return@withContext false
            if (lease.getBoolean("stop_requested", false))
                return@withContext true
            lease.edit().putBoolean("stop_requested", true).commit()
        }

    override suspend fun finish(sessionId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!lease.getBoolean("active", false) ||
                lease.getLong("id", -1L) != sessionId ||
                !lease.getBoolean("stop_requested", false))
                return@withContext false

            val status = db.readableDatabase.rawQuery(
                "SELECT status FROM sessions WHERE id=? LIMIT 1",
                arrayOf(sessionId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            when (status) {
                "RECORDING" -> {
                    val changed = db.writableDatabase.update("sessions",
                        ContentValues().apply {
                            put("status", "COMPLETED")
                            put("ended_at_ms", System.currentTimeMillis())
                        },
                        "id=? AND status='RECORDING'",
                        arrayOf(sessionId.toString()))
                    if (changed != 1) return@withContext false
                }
                "COMPLETED" -> Unit // retry only lease cleanup, never rewrite end time
                else -> return@withContext false
            }
            // Keep the same session ID until SQLite has confirmed completion.
            lease.edit().remove("active").remove("id")
                .remove("stop_requested").commit()
        }

    override suspend fun sampleCount(sessionId: Long): Long =
        withContext(Dispatchers.IO) {
            db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM samples WHERE session_id=?",
                arrayOf(sessionId.toString())
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
}
