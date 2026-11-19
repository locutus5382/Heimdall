package com.locutus5382.heimdall.recording

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.locutus5382.heimdall.engine.duko.DukoSessionContext
import com.locutus5382.heimdall.engine.duko.MeasurementOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-declared test setup, stored independently of the sensor time series.
 * The draft applies to NEXT START. Each new session receives its own copy;
 * renaming a future preset cannot retroactively rewrite earlier sessions.
 *
 * The engine never accesses this preference store, and stored user tags are
 * NEVER promoted to automatically verified hardware measurements.
 */
internal class DukoSessionContextStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(
        "duko_next_session_v1", Context.MODE_PRIVATE)
    private val helper by lazy { HeimdallDatabase.helper(app) }

    fun readDraft(): DukoSessionContext = DukoSessionContext(
        game = prefs.getString("game", "").orEmpty(),
        emulator = prefs.getString("emulator", "").orEmpty(),
        graphicsDriver = prefs.getString("driver", "").orEmpty(),
        performanceMode = prefs.getString("performance", "").orEmpty(),
        fanProfile = prefs.getString("fan", "").orEmpty(),
        clusterTuneProfile = prefs.getString("ct_profile", "Unknown").orEmpty(),
        clusterTuneEnabled = when (prefs.getString("ct_enabled", "?")) {
            "true" -> true
            "false" -> false
            else -> null
        },
        tuningTool = prefs.getString("tool", "").orEmpty(),
        notes = prefs.getString("notes", "").orEmpty(),
        origin = if (prefs.getBoolean("declared", false))
            MeasurementOrigin.USER_DECLARED else MeasurementOrigin.UNKNOWN,
    ).sanitized()

    fun saveDraft(context: DukoSessionContext) {
        val p = context.sanitized()
        prefs.edit()
            .putString("game", p.game)
            .putString("emulator", p.emulator)
            .putString("driver", p.graphicsDriver)
            .putString("performance", p.performanceMode)
            .putString("fan", p.fanProfile)
            .putString("ct_profile", p.clusterTuneProfile)
            .putString("ct_enabled", p.clusterTuneEnabled?.toString() ?: "?")
            .putString("tool", p.tuningTool)
            .putString("notes", p.notes)
            .putBoolean("declared", true)
            .apply()
    }

    /**
     * Called from LocalRecordingStore.begin's existing SQLite transaction.
     * Tags and capability profile are attached atomically to the session ID.
     * Failure aborts START rather than creating a context-less partial row.
     */
    fun attachNewSession(database: SQLiteDatabase, sessionId: Long) {
        val draft = readDraft()
        writeContext(database, sessionId, draft)
        val snapshot = DukoDeviceCapture.probe(app)
        val profile = ContentValues().apply {
            put("session_id", sessionId)
            put("contract_version", snapshot.contractVersion)
            put("captured_at_ms", snapshot.capturedAtMs)
            put("profile_json", DukoDeviceCapture.encode(snapshot))
        }
        database.insertOrThrow("duko_device_profiles", null, profile)
    }

    suspend fun get(sessionId: Long): DukoSessionContext = withContext(Dispatchers.IO) {
        require(sessionId > 0L)
        helper.readableDatabase.rawQuery(
            """SELECT game,emulator,graphics_driver,performance_mode,fan_profile,
                      cluster_tune_profile,cluster_tune_enabled,tuning_tool,notes,tag_source
               FROM duko_session_contexts WHERE session_id=? LIMIT 1""",
            arrayOf(sessionId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@use DukoSessionContext(
                origin = MeasurementOrigin.UNKNOWN)
            DukoSessionContext(
                game = c.getString(0).orEmpty(),
                emulator = c.getString(1).orEmpty(),
                graphicsDriver = c.getString(2).orEmpty(),
                performanceMode = c.getString(3).orEmpty(),
                fanProfile = c.getString(4).orEmpty(),
                clusterTuneProfile = c.getString(5) ?: "Unknown",
                clusterTuneEnabled = if (c.isNull(6)) null else c.getInt(6) == 1,
                tuningTool = c.getString(7).orEmpty(),
                notes = c.getString(8).orEmpty(),
                origin = runCatching {
                    MeasurementOrigin.valueOf(c.getString(9))
                }.getOrDefault(MeasurementOrigin.UNKNOWN)
            )
        }
    }

    suspend fun save(sessionId: Long, context: DukoSessionContext) =
        withContext(Dispatchers.IO) {
            require(sessionId > 0L)
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val existing = database.rawQuery(
                    "SELECT id FROM sessions WHERE id=? LIMIT 1",
                    arrayOf(sessionId.toString())
                ).use { it.moveToFirst() }
                check(existing) { "UNKNOWN_SESSION" }
                writeContext(database, sessionId,
                    context.sanitized().copy(origin = MeasurementOrigin.USER_DECLARED))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }

    suspend fun deviceProfileJson(sessionId: Long): String? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                "SELECT profile_json FROM duko_device_profiles WHERE session_id=?",
                arrayOf(sessionId.toString())
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }

    private fun writeContext(
        database: SQLiteDatabase, id: Long, context: DukoSessionContext
    ) {
        val p = context.sanitized()
        val values = ContentValues().apply {
            put("session_id", id)
            put("game", p.game)
            put("emulator", p.emulator)
            put("graphics_driver", p.graphicsDriver)
            put("performance_mode", p.performanceMode)
            put("fan_profile", p.fanProfile)
            put("cluster_tune_profile", p.clusterTuneProfile)
            if (p.clusterTuneEnabled == null) putNull("cluster_tune_enabled")
            else put("cluster_tune_enabled", if (p.clusterTuneEnabled) 1 else 0)
            put("tuning_tool", p.tuningTool)
            put("notes", p.notes)
            put("tag_source", p.origin.name)
            put("updated_at_ms", System.currentTimeMillis())
        }
        database.insertWithOnConflict(
            "duko_session_contexts", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        ).also { check(it > 0L) { "SESSION_CONTEXT_WRITE_FAILED" } }
    }
}
