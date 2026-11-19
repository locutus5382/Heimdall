package com.locutus5382.heimdall.recording

import android.content.Context
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoBatteryPoint
import com.locutus5382.heimdall.engine.duko.DukoEnergy
import com.locutus5382.heimdall.engine.duko.DukoEnergyReport
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoMetricAnalysis
import com.locutus5382.heimdall.engine.duko.DukoObservation
import com.locutus5382.heimdall.engine.duko.DukoSessionIntegrity
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.engine.duko.DukoStatistics
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.engine.duko.MeasurementOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * D2 Android data adapter: pulls only requested columns, on IO, then delegates
 * all calculation to the independently tested Kotlin engine. Historical
 * v1–v3 rows without monotonic time remain visible in legacy summaries and
 * are never silently treated as monotonic data.
 */
data class DukoFoundationReport(
    val sessionId: Long,
    val window: DukoWindow,
    val integrity: DukoSessionIntegrity,
    val energy: DukoEnergyReport,
    val capabilitySnapshotAvailable: Boolean,
    val analysisVersion: Int = 1,
)

internal class DukoSessionAnalyzer(
    context: Context, private val databaseName: String = HeimdallDatabase.NAME
) {
    private val app = context.applicationContext
    private val helper by lazy { HeimdallDatabase.helper(app, databaseName) }
    private val selectedMetrics = listOf(
        DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
        DukoMetric.CPU_CLOCK, DukoMetric.GPU_CLOCK,
        DukoMetric.BATTERY_TEMPERATURE, DukoMetric.BATTERY_LEVEL,
        DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE,
        DukoMetric.BATTERY_CURRENT, DukoMetric.BATTERY_VOLTAGE,
        DukoMetric.AVAILABLE_RAM,
    )

    suspend fun analyze(sessionId: Long): DukoFoundationReport? =
        withContext(Dispatchers.IO) {
            require(sessionId > 0L)
            val database = helper.readableDatabase
            val session = database.rawQuery(
                """SELECT status,end_reason,sample_interval_ms FROM sessions
                   WHERE id=? LIMIT 1""",
                arrayOf(sessionId.toString())
            ).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "COMPLETED")
                    return@withContext null
                (c.getString(1) ?: "COMPLETED") to
                    (if (c.isNull(2)) 2_000L else c.getLong(2))
            }
            val snapshot = database.rawQuery(
                """SELECT profile_json FROM duko_device_profiles
                   WHERE session_id=? LIMIT 1""",
                arrayOf(sessionId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val snapshotJson = runCatching {
                snapshot?.let { JSONObject(it) }
            }.getOrNull()
            val sourceForGpu = snapshotJson?.optString("gpuClockSource")
                ?.takeIf { it.isNotBlank() } ?: "GPU_SOURCE_UNVERIFIED"
            val sourceForGpuLoad = snapshotJson?.optString("gpuLoadSource")
                ?.takeIf { it.isNotBlank() } ?: "GPU_LOAD_UNVERIFIED"
            val cpuTempSource = snapshotJson?.optString("cpuTempSource")
                ?.takeIf { it.isNotBlank() } ?: "CPU_THERMAL_UNVERIFIED"
            val gpuTempSource = snapshotJson?.optString("gpuTempSource")
                ?.takeIf { it.isNotBlank() } ?: "GPU_THERMAL_UNVERIFIED"
            val series = selectedMetrics.associateWith {
                ArrayList<DukoObservation>()
            }
            val batteryPoints = ArrayList<DukoBatteryPoint>()
            var first: Long? = null
            var last: Long? = null
            database.rawQuery(
                """SELECT elapsed_ms,cpu_load_pct,gpu_load_pct,cpu_clock_mhz,
                          gpu_clock_mhz,battery_temp_c,battery_pct,
                          cpu_temp_c,gpu_temp_c,battery_current_ua,
                          battery_voltage_mv,free_memory_mib,battery_plugged
                   FROM samples WHERE session_id=? ORDER BY id""",
                arrayOf(sessionId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue // Legacy row, no valid elapsed clock
                    val t = c.getLong(0)
                    if (t < 0) continue
                    if (t < (first ?: Long.MAX_VALUE)) first = t
                    if (t > (last ?: Long.MIN_VALUE)) last = t
                    batteryPoints += DukoBatteryPoint(
                        elapsedMs = t,
                        currentMicroAmps = if (c.isNull(9)) null else c.getLong(9),
                        voltageMilliVolts = if (c.isNull(10)) null else c.getInt(10),
                        plugged = if (c.isNull(12)) null else c.getInt(12) != 0
                    )
                    for (i in selectedMetrics.indices) {
                        val metric = selectedMetrics[i]
                        val value = if (c.isNull(i + 1)) null
                            else c.getDouble(i + 1) /
                                if (metric == DukoMetric.BATTERY_CURRENT)
                                    1000.0 else 1.0
                        val (src, origin) = when (metric) {
                            DukoMetric.CPU_LOAD -> "/proc/stat" to
                                MeasurementOrigin.VALIDATED_SYSFS
                            DukoMetric.GPU_CLOCK -> sourceForGpu to
                                MeasurementOrigin.UNKNOWN
                            DukoMetric.GPU_LOAD -> sourceForGpuLoad to
                                MeasurementOrigin.UNKNOWN
                            DukoMetric.CPU_CLOCK -> "cpufreq-policy" to
                                MeasurementOrigin.VALIDATED_SYSFS
                            DukoMetric.CPU_TEMPERATURE -> cpuTempSource to
                                MeasurementOrigin.UNKNOWN
                            DukoMetric.GPU_TEMPERATURE -> gpuTempSource to
                                MeasurementOrigin.UNKNOWN
                            else -> "Android Battery/Memory API" to
                                MeasurementOrigin.ANDROID_API
                        }
                        series.getValue(metric).add(
                            DukoObservation(t, metric, value,
                                sourceId = src, origin = origin)
                        )
                    }
                }
            }
            val start = first ?: return@withContext null
            val end = last ?: return@withContext null
            if (end <= start) return@withContext null
            val window = DukoWindow(start, end)
            val cadence = session.second.coerceIn(250L, 60_000L)
            val reports = series.mapValues { (metric, observations) ->
                DukoStatistics.analyze(metric, window, observations, cadence)
            }
            val outcome = when (session.first) {
                "INTERRUPTED" -> DukoSessionOutcome.INTERRUPTED
                "COMPLETED" -> DukoSessionOutcome.COMPLETED
                else -> DukoSessionOutcome.NEEDS_RECOVERY
            }
            DukoFoundationReport(
                sessionId = sessionId,
                window = window,
                integrity = DukoSessionIntegrity(outcome, reports),
                energy = DukoEnergy.analyze(window, batteryPoints, cadence),
                capabilitySnapshotAvailable = snapshotJson != null
            )
        }
}
