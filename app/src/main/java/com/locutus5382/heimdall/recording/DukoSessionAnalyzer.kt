package com.locutus5382.heimdall.recording

import android.content.Context
import com.locutus5382.heimdall.engine.DukoAvailability
import com.locutus5382.heimdall.engine.DukoMetric
import com.locutus5382.heimdall.engine.DukoMetricStatistics
import com.locutus5382.heimdall.engine.DukoObservation
import com.locutus5382.heimdall.engine.DukoStatistics
import com.locutus5382.heimdall.engine.DukoTimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DukoSessionSummary(
    val sessionId: Long,
    val sampleIntervalMs: Long,
    val timeWindow: DukoTimeWindow?,
    val metricStatistics: List<DukoMetricStatistics>,
    val warning: String? = null
)

/**
 * DUKO D2 SQLite adapter. The mathematical definitions live entirely in
 * :engine. Legacy records without monotonic timestamps are deliberately not
 * granted synthetic "measured coverage" from wall-clock timestamps.
 */
internal class DukoSessionAnalyzer(context: Context) {
    private val db = HeimdallDatabase.helper(context.applicationContext)

    suspend fun analyze(sessionId: Long): DukoSessionSummary =
        withContext(Dispatchers.IO) {
            val database = db.readableDatabase
            val interval = database.rawQuery(
                "SELECT sample_interval_ms FROM sessions WHERE id=? LIMIT 1",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                check(cursor.moveToFirst()) { "SESSION_NOT_FOUND" }
                cursor.getLong(0).coerceIn(100L, 3_600_000L)
            }
            val columns = listOf(
                DukoMetric.CPU_UTILIZATION to "cpu_load_pct",
                DukoMetric.GPU_UTILIZATION to "gpu_load_pct",
                DukoMetric.CPU_CLOCK to "cpu_clock_mhz",
                DukoMetric.GPU_CLOCK to "gpu_clock_mhz",
                DukoMetric.BATTERY_TEMPERATURE to "battery_temp_c",
                DukoMetric.CPU_TEMPERATURE to "cpu_temp_c",
                DukoMetric.GPU_TEMPERATURE to "gpu_temp_c",
                DukoMetric.BATTERY_LEVEL to "battery_pct",
                DukoMetric.BATTERY_CURRENT to "battery_current_ua",
                DukoMetric.BATTERY_VOLTAGE to "battery_voltage_mv",
                DukoMetric.FREE_MEMORY to "free_memory_mib",
                DukoMetric.THERMAL_HEADROOM to "thermal_headroom",
            )
            val observations = columns.associate { it.first to ArrayList<DukoObservation>() }
            var minTimestamp: Long? = null
            var maxTimestamp: Long? = null
            var noMonotonicCount = 0
            database.rawQuery(
                "SELECT elapsed_ms,${columns.joinToString(",") { it.second }} " +
                    "FROM samples WHERE session_id=? ORDER BY id",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.isNull(0)) {
                        noMonotonicCount++
                        continue
                    }
                    val timestamp = cursor.getLong(0)
                    if (timestamp < 0) continue
                    minTimestamp = minOf(minTimestamp ?: timestamp, timestamp)
                    maxTimestamp = maxOf(maxTimestamp ?: timestamp, timestamp)
                    columns.forEachIndexed { i, (metric, _) ->
                        val value = if (cursor.isNull(i + 1)) null else cursor.getDouble(i + 1)
                        observations.getValue(metric).add(
                            DukoObservation(
                                metric = metric,
                                elapsedMs = timestamp,
                                value = value,
                                sourceId = when (metric) {
                                    DukoMetric.CPU_UTILIZATION -> "linux:/proc/stat"
                                    DukoMetric.GPU_UTILIZATION,
                                    DukoMetric.GPU_CLOCK -> "vendor:gpu"
                                    DukoMetric.CPU_CLOCK -> "linux:cpufreq"
                                    DukoMetric.CPU_TEMPERATURE,
                                    DukoMetric.GPU_TEMPERATURE -> "linux:thermal"
                                    DukoMetric.FREE_MEMORY -> "android:memory"
                                    DukoMetric.THERMAL_HEADROOM -> "android:power-manager"
                                    else -> "android:battery"
                                },
                                availability = if (value == null)
                                    DukoAvailability.TEMPORARILY_UNREADABLE
                                else DukoAvailability.VALID
                            )
                        )
                    }
                }
            }
            val start = minTimestamp
            val end = maxTimestamp
            if (start == null || end == null || end > Long.MAX_VALUE - interval)
                return@withContext DukoSessionSummary(
                    sessionId, interval, null, emptyList(),
                    "No monotonic timestamps recorded. Historical data remains available, " +
                        "but coverage-aware Duko analysis is unavailable."
                )
            val window = DukoTimeWindow(start, end + interval)
            val results = columns.map { (metric, _) ->
                DukoStatistics.summarize(
                    metric, observations.getValue(metric), window, interval
                )
            }
            DukoSessionSummary(
                sessionId, interval, window, results,
                warning = if (noMonotonicCount > 0)
                    "$noMonotonicCount legacy samples excluded: missing monotonic time"
                else null
            )
        }
}
