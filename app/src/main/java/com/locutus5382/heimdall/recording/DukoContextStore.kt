package com.locutus5382.heimdall.recording

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.locutus5382.heimdall.engine.DukoCpuPolicySpec
import com.locutus5382.heimdall.engine.DukoDeviceProfile
import com.locutus5382.heimdall.engine.DukoSessionContext
import com.locutus5382.heimdall.engine.DukoSetting
import com.locutus5382.heimdall.engine.DukoSettingOrigin
import com.locutus5382.heimdall.engine.DukoUnderclockDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * User test conditions are separate from immutable samples and the recorder
 * state machine. SQLite v4 preserves source profile snapshots and annotations
 * across recording revisions; edits never mutate raw telemetry.
 */
internal class DukoContextStore(
    context: Context,
    databaseName: String = HeimdallDatabase.NAME
) {
    private val app = context.applicationContext
    private val helper = HeimdallDatabase.helper(app, databaseName)

    suspend fun read(sessionId: Long): DukoSessionContext? = withContext(Dispatchers.IO) {
        val database = helper.readableDatabase
        val raw = database.rawQuery(
            "SELECT context_json FROM duko_session_meta WHERE session_id=?",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (raw != null) runCatching { parseContext(sessionId, JSONObject(raw)) }
            .getOrElse { DukoSessionContext(sessionId) }
        else if (hasSession(database, sessionId)) DukoSessionContext(sessionId)
        else null
    }

    suspend fun readDeviceProfileJson(sessionId: Long): String? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                "SELECT device_profile_json FROM duko_session_meta WHERE session_id=?",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0))
                    cursor.getString(0) else null
            }
        }

    suspend fun wasClusterTuneInstalled(sessionId: Long): Boolean? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                "SELECT clustertune_installed FROM duko_session_meta WHERE session_id=?",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0))
                    cursor.getInt(0) == 1 else null
            }
        }

    suspend fun save(context: DukoSessionContext): Boolean =
        withContext(Dispatchers.IO) {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                if (!hasSession(database, context.sessionId)) {
                    return@withContext false
                }
                val data = ContentValues().apply {
                    put("context_json", encodeContext(context).toString())
                    put("updated_at_ms", System.currentTimeMillis())
                }
                val changed = database.update(
                    "duko_session_meta", data, "session_id=?",
                    arrayOf(context.sessionId.toString())
                )
                if (changed == 0) {
                    data.put("session_id", context.sessionId)
                    database.insertOrThrow("duko_session_meta", null, data)
                }
                database.setTransactionSuccessful()
                true
            } finally {
                database.endTransaction()
            }
        }

    companion object {
        /** Must run inside the caller's already-open begin() transaction. */
        fun captureForNewSession(
            database: SQLiteDatabase,
            sessionId: Long,
            profile: DukoDeviceProfile?,
            installed: Boolean?
        ) {
            // The potentially slow hardware probe runs before the recorder's
            // SQLite transaction. Here we only write an already-captured
            // snapshot atomically alongside the new session.
            database.insertWithOnConflict(
                "duko_session_meta", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("context_json", encodeContext(DukoSessionContext(sessionId)).toString())
                    profile?.let { put("device_profile_json", encodeProfile(it).toString()) }
                    installed?.let { put("clustertune_installed", if (it) 1 else 0) }
                    put("captured_at_ms", System.currentTimeMillis())
                    put("updated_at_ms", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE
            )
        }

        private fun hasSession(db: SQLiteDatabase, id: Long): Boolean =
            db.rawQuery("SELECT 1 FROM sessions WHERE id=? LIMIT 1",
                arrayOf(id.toString())).use { it.moveToFirst() }

        private fun encodeSetting(setting: DukoSetting?): JSONObject? =
            setting?.let { JSONObject()
                .put("value", it.value)
                .put("origin", it.origin.name)
            }

        private fun readSetting(root: JSONObject, key: String): DukoSetting? {
            val item = root.optJSONObject(key) ?: return null
            val value = item.optString("value").trim().takeIf { it.isNotBlank() }
                ?: return null
            val source = DukoSettingOrigin.entries.firstOrNull {
                it.name == item.optString("origin")
            } ?: DukoSettingOrigin.UNKNOWN
            return DukoSetting(value, source)
        }

        private fun JSONObject.optional(key: String): String? =
            optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

        private fun encodeContext(context: DukoSessionContext): JSONObject =
            JSONObject().apply {
                put("schema", 1)
                put("sessionId", context.sessionId)
                put("game", context.game)
                put("emulator", context.emulator)
                put("emulatorVersion", context.emulatorVersion)
                put("driver", context.graphicsDriver)
                put("driverVersion", context.graphicsDriverVersion)
                put("performanceMode", encodeSetting(context.performanceMode))
                put("fanProfile", encodeSetting(context.fanProfile))
                put("underclockUsed", context.underclockUsed.name)
                put("tunerName", context.tunerName)
                put("tunerProfile", encodeSetting(context.tunerProfile))
                put("testProfile", context.testProfile)
                put("workloadMarker", context.workloadMarker)
                put("notes", context.notes)
            }

        private fun parseContext(sessionId: Long, root: JSONObject) =
            DukoSessionContext(
                sessionId = sessionId,
                game = root.optional("game"),
                emulator = root.optional("emulator"),
                emulatorVersion = root.optional("emulatorVersion"),
                graphicsDriver = root.optional("driver"),
                graphicsDriverVersion = root.optional("driverVersion"),
                performanceMode = readSetting(root, "performanceMode"),
                fanProfile = readSetting(root, "fanProfile"),
                underclockUsed = DukoUnderclockDeclaration.entries.firstOrNull {
                    it.name == root.optString("underclockUsed")
                } ?: DukoUnderclockDeclaration.UNKNOWN,
                tunerName = root.optional("tunerName"),
                tunerProfile = readSetting(root, "tunerProfile"),
                testProfile = root.optional("testProfile"),
                workloadMarker = root.optional("workloadMarker"),
                notes = root.optional("notes")
            )

        private fun encodeProfile(profile: DukoDeviceProfile): JSONObject =
            JSONObject().apply {
                put("contractVersion", profile.contractVersion)
                put("manufacturer", profile.manufacturer)
                put("model", profile.model)
                put("soc", profile.soc)
                put("androidApi", profile.androidApi)
                put("androidBuild", profile.androidBuild)
                put("kernelRelease", profile.kernelRelease)
                put("totalMemoryMiB", profile.totalMemoryMiB)
                put("cpuPolicies", JSONArray().apply {
                    profile.cpuPolicies.forEach { p: DukoCpuPolicySpec ->
                        put(JSONObject()
                            .put("id", p.id)
                            .put("cores", p.relatedCpuIds)
                            .put("hardwareMaxMHz", p.hardwareMaxMHz)
                            .put("policyMaxMHz", p.currentPolicyMaxMHz)
                            .put("observedClockMHz", p.currentClockMHz))
                    }
                })
                put("sensors", JSONArray().apply {
                    profile.sensors.forEach { s ->
                        put(JSONObject()
                            .put("metric", s.metric.name)
                            .put("unit", s.metric.unit.symbol)
                            .put("sourceId", s.source.id)
                            .put("sourceKind", s.source.kind.name)
                            .put("sourcePath", s.source.location)
                            .put("availability", s.source.availability.name))
                    }
                })
                put("fanSetting", profile.observedOemFanSetting?.value)
                put("performanceSetting", profile.observedOemPerformanceSetting?.value)
                put("policyRestrictionsAtStart", JSONArray().apply {
                    profile.policiesBelowReportedHardwareMax().forEach { put(it.id) }
                })
            }
    }
}
