package com.locutus5382.heimdall.recording

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Read-only view of the existing local recording database.
 *  Only the foreground recorder writes; the UI may list/export completed rows.
 */
data class SavedSession(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val samples: Long,
)

class SessionArchive(context: Context) {
    private val path = context.applicationContext.getDatabasePath("heimdall_v1.db")

    /** Read-only safety check used by Quick Settings after process recreation. */
    suspend fun unfinishedSessionId(): Long? = withContext(Dispatchers.IO) {
        if (!path.isFile) return@withContext null
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { database ->
                database.rawQuery(
                    "SELECT id FROM sessions WHERE status='RECORDING' ORDER BY id LIMIT 1",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            }
    }

    suspend fun recent(limit: Int = 20): List<SavedSession> = withContext(Dispatchers.IO) {
        if (!path.isFile) return@withContext emptyList()
        val count = limit.coerceIn(1, 100)
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { database ->
                database.rawQuery(
                    """
                    SELECT s.id, s.started_at_ms, s.ended_at_ms, COUNT(p.id)
                    FROM sessions s
                    LEFT JOIN samples p ON p.session_id=s.id
                    WHERE s.status='COMPLETED' AND s.ended_at_ms IS NOT NULL
                    GROUP BY s.id, s.started_at_ms, s.ended_at_ms
                    ORDER BY s.id DESC LIMIT $count
                    """.trimIndent(), null
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            SavedSession(
                                id = c.getLong(0),
                                startedAtMs = c.getLong(1),
                                endedAtMs = c.getLong(2),
                                samples = c.getLong(3)
                            )
                        )
                    }
                }
            }
    }

    /** A user-selected SAF destination; never uploads or overwrites source rows. */
    suspend fun exportCsv(sessionId: Long, destination: OutputStream): Long =
        withContext(Dispatchers.IO) {
            require(sessionId > 0L)
            check(path.isFile) { "NO_DATABASE" }
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { database ->
                    val complete = database.rawQuery(
                        "SELECT id FROM sessions WHERE id=? AND status='COMPLETED' LIMIT 1",
                        arrayOf(sessionId.toString())
                    ).use { c -> c.moveToFirst() }
                    check(complete) { "SESSION_NOT_COMPLETED" }
                    var written = 0L
                    destination.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        writer.write("sample_id,time_ms,cpu_load_pct,battery_pct,battery_temp_c,free_memory_mib,cpu_clock_mhz,gpu_clock_mhz\n")
                        database.rawQuery(
                            """
                            SELECT id, time_ms, cpu_load_pct, battery_pct, battery_temp_c,
                                   free_memory_mib, cpu_clock_mhz, gpu_clock_mhz
                            FROM samples WHERE session_id=? ORDER BY id
                            """.trimIndent(),
                            arrayOf(sessionId.toString())
                        ).use { c ->
                            while (c.moveToNext()) {
                                for (i in 0 until c.columnCount) {
                                    if (i > 0) writer.write(",")
                                    if (!c.isNull(i)) writer.write(c.getString(i))
                                }
                                writer.write("\n")
                                written++
                            }
                        }
                        writer.flush()
                    }
                    written
                }
        }
}
