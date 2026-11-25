package com.locutus5382.heimdall.recording

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User annotations are independent evidence: they never modify sensor readings. */
data class DukoSessionMarker(
    val id: Long,
    val sessionId: Long,
    val elapsedMs: Long,
    val label: String,
    val createdAtMs: Long,
)

class DukoSessionMarkerStore(
    context: Context,
    private val databaseName: String = HeimdallDatabase.NAME,
) {
    private val app = context.applicationContext
    private val helper by lazy { HeimdallDatabase.helper(app, databaseName) }

    companion object {
        const val MAX_PER_SESSION = 200
        const val MAX_LABEL_LENGTH = 120
        fun normalize(text: String): String = text.trim().take(MAX_LABEL_LENGTH)
    }

    suspend fun list(sessionId: Long): List<DukoSessionMarker> = withContext(Dispatchers.IO) {
        require(sessionId > 0L)
        val db = helper.readableDatabase
        db.rawQuery(
            """SELECT id, session_id, elapsed_ms, label, created_at_ms
               FROM duko_session_markers
               WHERE session_id=? ORDER BY elapsed_ms,id LIMIT $MAX_PER_SESSION""",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    DukoSessionMarker(cursor.getLong(0), cursor.getLong(1),
                        cursor.getLong(2), cursor.getString(3), cursor.getLong(4))
                )
            }
        }
    }

    /**
     * Accept a user-specified point in the recorded clock domain. Completed
     * recordings require the timestamp to be inside observed sample bounds.
     * Live markers require a still-recording session, an original recorder
     * start timestamp, and a nonfuture timestamp supplied at user click.
     */
    suspend fun add(
        sessionId: Long,
        elapsedMs: Long,
        label: String,
        live: Boolean = false,
    ): DukoSessionMarker = withContext(Dispatchers.IO) {
        require(sessionId > 0L && elapsedMs >= 0L)
        val text = normalize(label)
        require(text.isNotEmpty()) { "EMPTY_MARKER_LABEL" }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val session = db.rawQuery(
                """SELECT status,started_elapsed_ms FROM sessions WHERE id=? LIMIT 1""",
                arrayOf(sessionId.toString())
            ).use {
                if (!it.moveToFirst()) error("SESSION_NOT_FOUND")
                it.getString(0) to (if (it.isNull(1)) null else it.getLong(1))
            }
            if (live) {
                check(session.first == "RECORDING") { "SESSION_NOT_RECORDING" }
                check(session.second != null && elapsedMs >= session.second!! &&
                    elapsedMs <= nowElapsed) { "LIVE_MARKER_CLOCK_OUTSIDE_SESSION" }
            } else {
                check(session.first == "COMPLETED") { "SESSION_NOT_COMPLETED" }
                val bounds = db.rawQuery(
                    """SELECT MIN(elapsed_ms),MAX(elapsed_ms)
                       FROM samples WHERE session_id=? AND elapsed_ms>=0""",
                    arrayOf(sessionId.toString())
                ).use {
                    if (!it.moveToFirst() || it.isNull(0) || it.isNull(1))
                        null else it.getLong(0) to it.getLong(1)
                }
                check(bounds != null && elapsedMs in bounds.first..bounds.second) {
                    "MARKER_OUTSIDE_SAVED_SAMPLES"
                }
            }
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM duko_session_markers WHERE session_id=?",
                arrayOf(sessionId.toString())
            ).use { it.moveToFirst(); it.getInt(0) }
            check(count < MAX_PER_SESSION) { "TOO_MANY_SESSION_MARKERS" }
            val stamp = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put("session_id", sessionId)
                put("elapsed_ms", elapsedMs)
                put("label", text)
                put("created_at_ms", stamp)
            }
            val id = db.insertOrThrow("duko_session_markers", null, values)
            check(id > 0L) { "MARKER_INSERT_FAILED" }
            db.setTransactionSuccessful()
            DukoSessionMarker(id, sessionId, elapsedMs, text, stamp)
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(sessionId: Long, markerId: Long): Boolean =
        withContext(Dispatchers.IO) {
            require(sessionId > 0L && markerId > 0L)
            helper.writableDatabase.delete(
                "duko_session_markers", "session_id=? AND id=?",
                arrayOf(sessionId.toString(), markerId.toString())
            ) == 1
        }
}
