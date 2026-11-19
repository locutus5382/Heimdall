package com.locutus5382.heimdall.recording

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.AppendOutcome
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
    private val databaseName: String = HeimdallDatabase.NAME
) : RecordingPersistence {
    private val app = context.applicationContext
    private val db = HeimdallDatabase.helper(app, databaseName)
    private val dukoContextStore = DukoSessionContextStore(app)

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
                put("started_elapsed_ms", SystemClock.elapsedRealtime())
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_sdk", Build.VERSION.SDK_INT)
                put("app_version", runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull())
                put("sample_interval_ms", 2000)
                put("capability_summary", SensorCatalog.get(app).summary())
                put("status", "RECORDING")
            })
            check(id > 0L) { "SESSION_CREATE_FAILED" }
            // Snapshot the user's test labels and discovered device topology
            // in the SAME transaction. Metadata can change later without
            // modifying sensor samples or the recording ID.
            dukoContextStore.attachNewSession(database, id)
            database.setTransactionSuccessful()
            id
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun sessionStartElapsedMs(sessionId: Long): Long? =
        withContext(Dispatchers.IO) {
            db.readableDatabase.rawQuery(
                "SELECT started_elapsed_ms FROM sessions WHERE id=? LIMIT 1",
                arrayOf(sessionId.toString())
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }

    override suspend fun append(sessionId: Long, sample: DeviceSample): Boolean =
        appendOutcome(sessionId, sample) == AppendOutcome.WRITTEN

    override suspend fun appendOutcome(
        sessionId: Long, sample: DeviceSample
    ): AppendOutcome = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val writable = database.rawQuery(
                """SELECT id FROM sessions WHERE id=? AND status='RECORDING'
                   AND stop_requested_at_ms IS NULL LIMIT 1""",
                arrayOf(sessionId.toString())
            ).use { it.moveToFirst() }
            if (!writable) return@withContext AppendOutcome.STOP_REQUESTED
            val row = ContentValues().apply {
                put("session_id", sessionId)
                put("time_ms", sample.observedAtMs)
                sample.cpuLoadPercent?.let { put("cpu_load_pct", it) }
                sample.batteryLevelPercent?.let { put("battery_pct", it) }
                sample.batteryTemperatureC?.let { put("battery_temp_c", it) }
                sample.availableMemoryMiB?.let { put("free_memory_mib", it) }
                sample.cpuClockMHz?.let { put("cpu_clock_mhz", it) }
                sample.gpuClockMHz?.let { put("gpu_clock_mhz", it) }
                sample.observedElapsedMs?.let { put("elapsed_ms", it) }
                sample.thermalStatus?.let { put("thermal_status", it) }
                sample.thermalHeadroom?.takeIf { it.isFinite() }
                    ?.let { put("thermal_headroom", it) }
                sample.batteryCurrentUa?.let { put("battery_current_ua", it) }
                sample.batteryVoltageMv?.let { put("battery_voltage_mv", it) }
                sample.batteryPlugged?.let { put("battery_plugged", if (it) 1 else 0) }
                sample.gpuLoadPercent?.let { put("gpu_load_pct", it) }
                sample.cpuTemperatureC?.let { put("cpu_temp_c", it) }
                sample.gpuTemperatureC?.let { put("gpu_temp_c", it) }
                fun encode(m: Map<String, Long>): String =
                    m.entries.sortedBy { it.key }.joinToString("|") {
                        "${it.key}=${it.value}"
                    }
                if (sample.cpuPolicyClocksMHz.isNotEmpty())
                    put("cpu_policy_clocks", encode(sample.cpuPolicyClocksMHz))
                if (sample.cpuPolicyLimitsMHz.isNotEmpty())
                    put("cpu_policy_limits", encode(sample.cpuPolicyLimitsMHz))
                if (sample.cpuPolicyHardwareMaxMHz.isNotEmpty())
                    put("cpu_policy_hardware_max",
                        encode(sample.cpuPolicyHardwareMaxMHz))
            }
            val saved = database.insertOrThrow("samples", null, row) > 0L
            if (saved) database.setTransactionSuccessful()
            if (saved) AppendOutcome.WRITTEN else AppendOutcome.RETRYABLE_ERROR
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
                """SELECT status, started_at_ms, stop_requested_at_ms
                   FROM sessions WHERE id=? LIMIT 1""",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else Triple(
                    cursor.getString(0),
                    cursor.getLong(1),
                    if (cursor.isNull(2)) null else cursor.getLong(2)
                )
            }
            val completed = when {
                status == null -> false
                status.first == "COMPLETED" -> true // Immutable prior finalization.
                status.first != "RECORDING" || status.third == null -> false
                else -> {
                    val total = countSamples(database, sessionId)
                    database.update(
                        "sessions",
                        ContentValues().apply {
                            put("status", "COMPLETED")
                            // Preserve durable STOP time, not next-day recovery.
                            put("ended_at_ms", maxOf(status.second, requireNotNull(status.third)))
                            put("end_reason", "COMPLETED")
                            put("sample_count", total)
                        },
                        "id=? AND status='RECORDING' AND stop_requested_at_ms IS NOT NULL",
                        arrayOf(sessionId.toString())
                    ) == 1
                }
            }
            if (completed) database.setTransactionSuccessful()
            completed
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Called only from an engine which owns no running sampler, on cold
     * restoration. Never silently treats lost sampling as normal completion.
     */
    override suspend fun recoverInterruptedSession(): Long? =
        withContext(Dispatchers.IO) {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                val open = database.rawQuery(
                    """SELECT id, started_at_ms FROM sessions
                       WHERE status='RECORDING' ORDER BY id""", null
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else {
                        val id = cursor.getLong(0)
                        val start = cursor.getLong(1)
                        if (cursor.moveToNext()) null else id to start
                    }
                } ?: return@withContext null
                val id = open.first
                val lastSample = database.rawQuery(
                    "SELECT MAX(time_ms) FROM samples WHERE session_id=?",
                    arrayOf(id.toString())
                ).use { cur ->
                    if (cur.moveToFirst() && !cur.isNull(0)) cur.getLong(0) else null
                }
                val total = countSamples(database, id)
                val changed = database.update(
                    "sessions",
                    ContentValues().apply {
                        put("status", "COMPLETED")
                        put("ended_at_ms", maxOf(open.second, lastSample ?: open.second))
                        put("end_reason", "INTERRUPTED")
                        put("sample_count", total)
                    },
                    "id=? AND status='RECORDING' AND stop_requested_at_ms IS NULL",
                    arrayOf(id.toString())
                ) == 1
                if (changed) {
                    database.setTransactionSuccessful()
                    id
                } else null // Durable STOP may have been committed meanwhile.
            } finally {
                database.endTransaction()
            }
        }

    private fun countSamples(database: SQLiteDatabase, id: Long): Long =
        database.rawQuery("SELECT COUNT(*) FROM samples WHERE session_id=?",
            arrayOf(id.toString())).use { cur ->
            if (cur.moveToFirst()) cur.getLong(0) else 0L
        }

    override suspend fun sampleCount(sessionId: Long): Long = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM samples WHERE session_id=?",
            arrayOf(sessionId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }
}
