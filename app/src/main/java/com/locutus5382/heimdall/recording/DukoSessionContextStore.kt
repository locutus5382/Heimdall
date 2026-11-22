package com.locutus5382.heimdall.recording

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.locutus5382.heimdall.engine.RecordingPreparation
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
internal class DukoSessionContextStore(
    context: Context, private val databaseName: String = HeimdallDatabase.NAME
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(
        "duko_next_session_v1", Context.MODE_PRIVATE)
    private val helper by lazy { HeimdallDatabase.helper(app, databaseName) }

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

    fun isNextOnly(): Boolean = prefs.getBoolean("one_session_only", false)

    fun saveDraft(context: DukoSessionContext, nextOnly: Boolean = isNextOnly()) {
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
            .putBoolean("one_session_only", nextOnly)
            .putLong("draft_revision", System.nanoTime())
            .apply()
    }

    /**
     * Probe the hardware BEFORE acquiring SQLite's writer lock. Some vendor
     * cpufreq/thermal sysfs reads can block, especially when SELinux denies
     * access. Snapshot setup must not stall concurrent session exports.
     */
    data class PreparedSessionMetadata(
        val context: DukoSessionContext,
        val contractVersion: Int,
        val capturedAtMs: Long,
        val profileJson: String,
        val nextOnlyRevision: Long? = null,
    ) : RecordingPreparation

    fun prepareNewSession(): PreparedSessionMetadata {
        val draft = readDraft()
        // Revision protects a newly edited draft from being consumed by an
        // earlier START whose capability probe was still in flight.
        val revision = if (isNextOnly() &&
            prefs.getBoolean("declared", false))
            prefs.getLong("draft_revision", 0L) else null
        val snapshot = DukoDeviceCapture.probe(app)
        return PreparedSessionMetadata(
            context = draft, contractVersion = snapshot.contractVersion,
            capturedAtMs = snapshot.capturedAtMs,
            profileJson = DukoDeviceCapture.encode(snapshot),
            nextOnlyRevision = revision,
        )
    }

    /**
     * Consume one-run tags ONLY after SQLite's START transaction commits.
     * An edited draft has a different revision and must not be erased.
     * This is crash-safe for the recorded snapshot, but preference clearing is
     * best-effort across a process death between commit and clearing.
     */
    fun consumeNextOnlyAfterCommit(prepared: PreparedSessionMetadata) {
        val revision = prepared.nextOnlyRevision ?: return
        if (!isNextOnly() || !prefs.getBoolean("declared", false) ||
            prefs.getLong("draft_revision", 0L) != revision) return
        // This preferences file contains only next-session test conditions.
        prefs.edit().clear().commit()
    }

    /**
     * Only the two cheap inserts run within the existing START transaction.
     * If either fails, START rolls back its session row and retains all prior
     * recordings. Nothing in this method probes the hardware.
     */
    fun attachNewSession(
        database: SQLiteDatabase,
        sessionId: Long,
        prepared: PreparedSessionMetadata,
    ) {
        writeContext(database, sessionId, prepared.context)
        val profile = ContentValues().apply {
            put("session_id", sessionId)
            put("contract_version", prepared.contractVersion)
            put("captured_at_ms", prepared.capturedAtMs)
            put("profile_json", prepared.profileJson)
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
            when (val active = p.clusterTuneEnabled) {
                null -> putNull("cluster_tune_enabled")
                else -> put("cluster_tune_enabled", if (active) 1 else 0)
            }
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
