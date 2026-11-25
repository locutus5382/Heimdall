package com.locutus5382.heimdall.recording

import android.database.sqlite.SQLiteDatabase

/**
 * Non-mutating one-session audit for Odin acceptance. This reads the
 * materialized completion count and exactly one indexed source-row count.
 * A mismatch is reported rather than "repaired" by rewriting evidence.
 */
internal data class LatestSessionAudit(
    val sessionId: Long,
    val declaredRows: Long,
    val actualRows: Long,
) {
    val consistent: Boolean get() = actualRows == declaredRows
}

internal fun latestSavedSessionAudit(database: SQLiteDatabase): LatestSessionAudit? {
    val latest = database.rawQuery(
        """SELECT id, sample_count FROM sessions
           WHERE status='COMPLETED' ORDER BY id DESC LIMIT 1""", null
    ).use { cursor ->
        if (cursor.moveToFirst())
            cursor.getLong(0) to cursor.getLong(1)
        else null
    } ?: return null
    val count = database.rawQuery(
        "SELECT COUNT(*) FROM samples WHERE session_id=?",
        arrayOf(latest.first.toString())
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else -1L
    }
    return LatestSessionAudit(latest.first, latest.second, count)
}
