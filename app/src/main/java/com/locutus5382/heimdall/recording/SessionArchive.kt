package com.locutus5382.heimdall.recording

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.locutus5382.heimdall.engine.MetricStatistics
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.engine.duko.DukoComparison
import com.locutus5382.heimdall.engine.duko.DukoComparisonInput
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoMultiRun
import com.locutus5382.heimdall.engine.duko.DukoMultiRunReport
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.engine.SessionStatistics
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
    val reason: String = "COMPLETED",
)

data class SessionOverview(
    val cpuLoad: MetricStatistics?,
    val gpuClock: MetricStatistics?,
    val highestBatteryTempC: Double?,
    val batteryAtStart: Int?,
    val batteryAtEnd: Int?,
    val totalSamples: Long,
)

class SessionArchive(
    context: Context,
    private val databaseName: String = HeimdallDatabase.NAME
) {
    private val app = context.applicationContext
    private val path = app.getDatabasePath(databaseName)
    private val helper by lazy { HeimdallDatabase.helper(app, databaseName) }

    /** Read-only safety check used by Quick Settings after process recreation. */
    suspend fun unfinishedSessionId(): Long? = withContext(Dispatchers.IO) {
        if (!path.isFile) return@withContext null
        run { val database = helper.readableDatabase
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
        run { val database = helper.readableDatabase
                database.rawQuery(
                    """
                    SELECT s.id, s.started_at_ms, s.ended_at_ms,
                           s.sample_count, s.end_reason
                    FROM sessions s
                    WHERE s.status='COMPLETED' AND s.ended_at_ms IS NOT NULL
                    ORDER BY s.id DESC LIMIT $count
                    """.trimIndent(), null
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            SavedSession(
                                id = c.getLong(0),
                                startedAtMs = c.getLong(1),
                                endedAtMs = c.getLong(2),
                                samples = c.getLong(3),
                                reason = c.getString(4) ?: "COMPLETED"
                            )
                        )
                    }
                }
            }
    }

    /** Calculate a summary only on demand, on IO, for a completed recording. */
    suspend fun overview(sessionId: Long): SessionOverview = withContext(Dispatchers.IO) {
        require(sessionId > 0L)
        check(path.isFile) { "NO_DATABASE" }
        run { val database = helper.readableDatabase
                val completed = database.rawQuery(
                    "SELECT id FROM sessions WHERE id=? AND status='COMPLETED' LIMIT 1",
                    arrayOf(sessionId.toString())
                ).use { it.moveToFirst() }
                check(completed) { "SESSION_NOT_COMPLETED" }
                val cpu = ArrayList<Double>()
                val gpu = ArrayList<Double>()
                var maxTemp: Double? = null
                var initialBattery: Int? = null
                var lastBattery: Int? = null
                var total = 0L
                database.rawQuery(
                    """SELECT cpu_load_pct, gpu_clock_mhz, battery_temp_c, battery_pct
                       FROM samples WHERE session_id=? ORDER BY id""",
                    arrayOf(sessionId.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        total++
                        if (!cursor.isNull(0)) cpu.add(cursor.getDouble(0))
                        if (!cursor.isNull(1)) gpu.add(cursor.getDouble(1))
                        if (!cursor.isNull(2)) {
                            val value = cursor.getDouble(2)
                            if (value.isFinite() &&
                                (maxTemp == null || value > maxTemp!!))
                                maxTemp = value
                        }
                        if (!cursor.isNull(3)) {
                            val value = cursor.getInt(3)
                            if (initialBattery == null) initialBattery = value
                            lastBattery = value
                        }
                    }
                }
                SessionOverview(
                    SessionStatistics.of(cpu), SessionStatistics.of(gpu),
                    maxTemp, initialBattery, lastBattery, total
                )
            }
    }

    /**
     * M4a: optional recorded monotonic interval. An out-of-range selection
     * returns null; requested endpoints are never extrapolated or shifted.
     */
    suspend fun dukoFoundation(
        sessionId: Long,
        selectedWindow: DukoWindow? = null,
    ): DukoFoundationReport? =
        DukoSessionAnalyzer(app, databaseName).analyze(sessionId, selectedWindow)


    /**
     * M5c: exact recorded monotonic extrema, never wall-clock duration.
     * A completed session with fewer than two distinct monotonic ticks
     * cannot provide a meaningful bounded comparison interval.
     */
    suspend fun dukoMonotonicBounds(sessionId: Long): DukoWindow? =
        withContext(Dispatchers.IO) {
            require(sessionId > 0L)
            if (!path.isFile) return@withContext null
            val database = helper.readableDatabase
            val completed = database.rawQuery(
                "SELECT id FROM sessions WHERE id=? AND status='COMPLETED' LIMIT 1",
                arrayOf(sessionId.toString())
            ).use { it.moveToFirst() }
            if (!completed) return@withContext null
            database.rawQuery(
                """SELECT MIN(elapsed_ms), MAX(elapsed_ms) FROM samples
                   WHERE session_id=? AND elapsed_ms>=0""",
                arrayOf(sessionId.toString())
            ).use { c ->
                if (!c.moveToFirst() || c.isNull(0) || c.isNull(1))
                    return@withContext null
                val start = c.getLong(0)
                val end = c.getLong(1)
                if (start < 0L || end <= start) null else DukoWindow(start, end)
            }
        }

    /**
     * M5b: compare two distinct, completed, independently analyzed sessions.
     * Reads are bounded by each selected window and run off the main thread.
     * Context tags remain user declarations, never verified hardware state.
     */
    suspend fun dukoCompare(
        sessionA: Long,
        sessionB: Long,
        windowA: DukoWindow? = null,
        windowB: DukoWindow? = null,
    ): DukoComparisonReport? = withContext(Dispatchers.IO) {
        require(sessionA > 0L && sessionB > 0L && sessionA != sessionB) {
            "TWO_DISTINCT_SESSIONS_REQUIRED"
        }
        val analyzer = DukoSessionAnalyzer(app, databaseName)
        val a = analyzer.analyze(sessionA, windowA) ?: return@withContext null
        val b = analyzer.analyze(sessionB, windowB) ?: return@withContext null
        val contexts = DukoSessionContextStore(app, databaseName)
        DukoComparison.compare(
            DukoComparisonInput(
                sessionId = sessionA,
                window = a.window,
                outcome = a.integrity.outcome,
                context = contexts.get(sessionA),
                metrics = a.integrity.metrics,
            ),
            DukoComparisonInput(
                sessionId = sessionB,
                window = b.window,
                outcome = b.integrity.outcome,
                context = contexts.get(sessionB),
                metrics = b.integrity.metrics,
            ),
        )
    }

    /**
     * M2.1 multi-run comparisons are strictly read-only and bounded:
     * 2-6 distinct completed recordings, each with its OWN exact monotonic
     * selection. Source measurements are analyzed independently, then
     * released after assembling small immutable evidence summaries.
     */
    suspend fun dukoMultiRun(
        selections: List<Pair<Long, DukoWindow>>,
    ): DukoMultiRunReport? = withContext(Dispatchers.IO) {
        require(selections.size in DukoMultiRun.MIN_RUNS..DukoMultiRun.MAX_RUNS) {
            "TWO_TO_SIX_RUNS_REQUIRED"
        }
        val ids = selections.map { it.first }
        require(ids.all { it > 0L } && ids.toSet().size == ids.size) {
            "DISTINCT_VALID_SESSIONS_REQUIRED"
        }
        val analyzer = DukoSessionAnalyzer(app, databaseName)
        val contexts = DukoSessionContextStore(app, databaseName)
        val inputs = ArrayList<DukoComparisonInput>(selections.size)
        for ((sessionId, window) in selections) {
            val report = analyzer.analyze(sessionId, window)
                ?: return@withContext null
            inputs += DukoComparisonInput(
                sessionId = sessionId,
                window = report.window,
                outcome = report.integrity.outcome,
                context = contexts.get(sessionId),
                metrics = report.integrity.metrics,
            )
        }
        DukoMultiRun.compare(inputs)
    }

    /** A user-selected SAF destination; never uploads or overwrites source rows. */
    suspend fun exportCsv(
        sessionId: Long,
        destination: OutputStream,
        selectedWindow: DukoWindow? = null,
    ): Long =
        withContext(Dispatchers.IO) {
            require(sessionId > 0L)
            check(path.isFile) { "NO_DATABASE" }
            run { val database = helper.readableDatabase
                    val complete = database.rawQuery(
                        "SELECT id FROM sessions WHERE id=? AND status='COMPLETED' LIMIT 1",
                        arrayOf(sessionId.toString())
                    ).use { c -> c.moveToFirst() }
                    check(complete) { "SESSION_NOT_COMPLETED" }
                    if (selectedWindow != null) {
                        val bounds = database.rawQuery(
                            """SELECT MIN(elapsed_ms),MAX(elapsed_ms) FROM samples
                               WHERE session_id=? AND elapsed_ms>=0""",
                            arrayOf(sessionId.toString())
                        ).use {
                            if (!it.moveToFirst() || it.isNull(0) || it.isNull(1))
                                null else it.getLong(0) to it.getLong(1)
                        }
                        require(bounds != null &&
                            selectedWindow.startMs >= bounds.first &&
                            selectedWindow.endMs <= bounds.second) {
                            "SELECTED_WINDOW_OUTSIDE_RECORDING"
                        }
                    }
                    // Keep original sample IDs and both clocks, including when
                    // a user selects a short window in the middle of a run.
                    val table = if (selectedWindow == null) "samples" else
                        "samples INDEXED BY samples_session_elapsed_idx"
                    val where = if (selectedWindow == null) "" else
                        " AND elapsed_ms>=? AND elapsed_ms<=?"
                    val args = if (selectedWindow == null)
                        arrayOf(sessionId.toString()) else arrayOf(
                            sessionId.toString(),
                            selectedWindow.startMs.toString(),
                            selectedWindow.endMs.toString()
                        )
                    var written = 0L
                    destination.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        writer.write(
                            "sample_id,time_ms,cpu_load_pct,battery_pct,battery_temp_c,free_memory_mib,cpu_clock_mhz,gpu_clock_mhz," +
                            "elapsed_ms,thermal_status,thermal_headroom,battery_current_ua,battery_voltage_mv,battery_plugged,gpu_load_pct," +
                            "cpu_temp_c,gpu_temp_c,cpu_policy_clocks,cpu_policy_limits,cpu_policy_hardware_max," +
                            "battery_raw_current_ua,battery_raw_voltage_mv,battery_status," +
                            "battery_charge_counter_uah,battery_energy_counter_nwh," +
                            "thermal_headroom_observed_elapsed_ms," +
                            "cpu_core_load_pct,psi_cpu_some_avg10," +
                            "psi_memory_some_avg10,psi_memory_full_avg10\n"
                        )
                        database.rawQuery(
                            """
                            SELECT id, time_ms, cpu_load_pct, battery_pct, battery_temp_c,
                                   free_memory_mib, cpu_clock_mhz, gpu_clock_mhz,
                                   elapsed_ms, thermal_status, thermal_headroom,
                                   battery_current_ua, battery_voltage_mv,
                                   battery_plugged, gpu_load_pct, cpu_temp_c, gpu_temp_c,
                                   cpu_policy_clocks, cpu_policy_limits, cpu_policy_hardware_max,
                                   battery_raw_current_ua, battery_raw_voltage_mv, battery_status,
                                   battery_charge_counter_uah, battery_energy_counter_nwh,
                                   thermal_headroom_observed_elapsed_ms,
                                   cpu_core_load_pct,psi_cpu_some_avg10,
                                   psi_memory_some_avg10,psi_memory_full_avg10
                            FROM $table WHERE session_id=?$where ORDER BY id
                            """.trimIndent(),
                            args
                        ).use { c ->
                            while (c.moveToNext()) {
                                for (i in 0 until c.columnCount) {
                                    if (i > 0) writer.write(",")
                                    if (!c.isNull(i)) {
                                        val cell = c.getString(i)
                                        // Text columns such as policy maps may
                                        // contain commas, quotes or line breaks.
                                        // Quote as RFC4180 instead of shifting CSV columns.
                                        if (cell.any { it == ',' || it == '"' ||
                                                it == '\n' || it == '\r' }) {
                                            writer.write("\"")
                                            writer.write(cell.replace("\"", "\"\""))
                                            writer.write("\"")
                                        } else writer.write(cell)
                                    }
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
