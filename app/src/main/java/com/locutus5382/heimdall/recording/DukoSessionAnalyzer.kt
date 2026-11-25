package com.locutus5382.heimdall.recording

import android.content.Context
import android.os.BatteryManager
import com.locutus5382.heimdall.engine.duko.ClockResidencyAccumulator
import com.locutus5382.heimdall.engine.duko.ClockResidencyResult
import com.locutus5382.heimdall.engine.duko.ClockResidencySample
import com.locutus5382.heimdall.engine.duko.ReadingValidity
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoBatteryPoint
import com.locutus5382.heimdall.engine.duko.DukoBatteryStatus
import com.locutus5382.heimdall.engine.duko.DukoEnergyAccumulator
import com.locutus5382.heimdall.engine.duko.DukoConfidence
import com.locutus5382.heimdall.engine.duko.DukoConfidenceReport
import com.locutus5382.heimdall.engine.duko.DukoEnergyReport
import com.locutus5382.heimdall.engine.duko.DukoThermalAccumulator
import com.locutus5382.heimdall.engine.duko.ThermalStabilityAccumulator
import com.locutus5382.heimdall.engine.duko.ThermalStabilityResult
import com.locutus5382.heimdall.engine.duko.DukoThermalReport
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.DukoMetricAnalysis
import com.locutus5382.heimdall.engine.duko.DukoObservation
import com.locutus5382.heimdall.engine.duko.DukoSessionIntegrity
import com.locutus5382.heimdall.engine.duko.DukoSessionOutcome
import com.locutus5382.heimdall.engine.duko.DukoStatisticsAccumulator
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.engine.duko.MeasurementOrigin
import com.locutus5382.heimdall.engine.duko.CoreLoadCodec
import com.locutus5382.heimdall.engine.duko.CpuPolicyTopology
import com.locutus5382.heimdall.engine.duko.CoreActivityAccumulator
import com.locutus5382.heimdall.engine.duko.CoreActivityReport
import com.locutus5382.heimdall.engine.duko.FrequencyCapTimeline
import com.locutus5382.heimdall.engine.duko.FrequencyCapTimelineAccumulator
import com.locutus5382.heimdall.engine.duko.TimedSignalAccumulator
import com.locutus5382.heimdall.engine.duko.TimedSignalSummary
import com.locutus5382.heimdall.engine.duko.DukoMinuteAccumulator
import com.locutus5382.heimdall.engine.duko.DukoMinuteReport
import com.locutus5382.heimdall.engine.duko.CpuPolicyResidencyAccumulator
import com.locutus5382.heimdall.engine.duko.CpuPolicyResidencyReport
import com.locutus5382.heimdall.engine.duko.DukoEventAccumulator
import com.locutus5382.heimdall.engine.duko.DukoEventSample
import com.locutus5382.heimdall.engine.duko.DukoEventTimeline
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
    val thermal: Map<DukoMetric, DukoThermalReport>,
    val capabilitySnapshotAvailable: Boolean,
    val confidence: DukoConfidenceReport,
    val gpuClockResidency: ClockResidencyResult? = null,
    val thermalStability: Map<DukoMetric, ThermalStabilityResult> = emptyMap(),
    val coreActivity: CoreActivityReport? = null,
    val frequencyCaps: FrequencyCapTimeline? = null,
    val timelines: Map<String, TimedSignalSummary> = emptyMap(),
    val perCoreTimelines: Map<Int, TimedSignalSummary> = emptyMap(),
    val declaredClusterTuneEnabled: Boolean? = null,
    val minuteAnalysis: DukoMinuteReport? = null,
    val cpuPolicyResidency: List<CpuPolicyResidencyReport> = emptyList(),
    val events: DukoEventTimeline? = null,
    val analysisVersion: Int = 4,
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

    /**
     * An explicit monotonic window is evaluated against the recorded session
     * range. Invalid/out-of-range selections return null; in-range empty
     * selections produce INSUFFICIENT evidence instead of fabricated samples.
     * Never load and slice the whole session in Kotlin for a small selection.
     */
    suspend fun analyze(
        sessionId: Long,
        selectedWindow: DukoWindow? = null,
    ): DukoFoundationReport? = withContext(Dispatchers.IO) {
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
            // Declared tuner state is metadata, never an inference or causal claim.
            val declaredClusterTuneEnabled: Boolean? = database.rawQuery(
                "SELECT cluster_tune_enabled FROM duko_session_contexts " +
                    "WHERE session_id=? LIMIT 1",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) null
                else cursor.getInt(0) == 1
            }
            val sourceForGpu = snapshotJson?.optString("gpuClockSource")
                ?.takeIf { it.isNotBlank() } ?: "GPU_SOURCE_UNVERIFIED"
            val sourceForGpuLoad = snapshotJson?.optString("gpuLoadSource")
                ?.takeIf { it.isNotBlank() } ?: "GPU_LOAD_UNVERIFIED"
            val cpuTempSource = snapshotJson?.optString("cpuTempSource")
                ?.takeIf { it.isNotBlank() } ?: "CPU_THERMAL_UNVERIFIED"
            val gpuTempSource = snapshotJson?.optString("gpuTempSource")
                ?.takeIf { it.isNotBlank() } ?: "GPU_THERMAL_UNVERIFIED"
            // Full-session analysis obtains bounds without materializing all
            // 11 sensor histories. The v6 range index serves this query too.
            val recorded = database.rawQuery(
                """SELECT MIN(elapsed_ms), MAX(elapsed_ms) FROM samples
                   WHERE session_id=? AND elapsed_ms>=0""",
                arrayOf(sessionId.toString())
            ).use { c ->
                if (!c.moveToFirst() || c.isNull(0) || c.isNull(1))
                    null else c.getLong(0) to c.getLong(1)
            } ?: return@withContext null
            if (selectedWindow != null &&
                (selectedWindow.startMs < recorded.first ||
                    selectedWindow.endMs > recorded.second))
                return@withContext null
            val window = selectedWindow ?: run {
                if (recorded.second <= recorded.first) return@withContext null
                DukoWindow(recorded.first, recorded.second)
            }
            val cadence = session.second.coerceIn(250L, 60_000L)
            val policies = linkedMapOf<String, Set<Int>>()
            snapshotJson?.optJSONArray("cpuPolicies")?.let { array ->
                for (i in 0 until array.length()) {
                    val p = array.optJSONObject(i) ?: continue
                    val id = p.optString("id")
                    val cores = CpuPolicyTopology.parse(p.optString("relatedCpus"))
                    if (id.matches(Regex("policy[0-9]+")) && cores.isNotEmpty())
                        policies[id] = cores
                }
            }
            val coreActivity = CoreActivityAccumulator(window, policies, cadence)
            val frequencyCaps = FrequencyCapTimelineAccumulator(window, cadence)
            val minuteAnalysis = DukoMinuteAccumulator(window, cadence)
            val policyResidency = CpuPolicyResidencyAccumulator(window, policies, cadence)
            val eventTimeline = DukoEventAccumulator(window, cadence)
            // Fixed-size real-data plot buckets. Each selected interval gets
            // its own actual-sample extrema, mean, standard deviation and trace.
            val timelineSpecs = linkedMapOf(
                "CPU LOAD" to "%",
                "GPU LOAD" to "%",
                "CPU CLOCK" to "MHz",
                "GPU CLOCK" to "MHz",
                "BATTERY TEMP" to "°C",
                "CPU TEMP" to "°C",
                "GPU TEMP" to "°C",
                "CPU PSI SOME AVG10" to "%",
                "MEMORY PSI SOME AVG10" to "%",
                "MEMORY PSI FULL AVG10" to "%",
            )
            val timelines = timelineSpecs.mapValues { (label, unit) ->
                TimedSignalAccumulator(label, unit, window)
            }
            val coreTimelines = mutableMapOf<Int, TimedSignalAccumulator>()
            fun policyMap(raw: String?): Map<String, Long> =
                raw?.take(5000)?.split("|")?.take(128)?.mapNotNull { item ->
                    val pair = item.split('=', limit = 2)
                    if (pair.size != 2 ||
                        !pair[0].matches(Regex("policy[0-9]+"))) null
                    else pair[1].toLongOrNull()?.takeIf { it > 0L }
                        ?.let { pair[0] to it }
                }?.toMap().orEmpty()
            val accumulators = selectedMetrics.associateWith {
                DukoStatisticsAccumulator(it, window, cadence)
            }
            // Scalar and battery-energy statistics consume the SQLite cursor.
            // Thermal trends retain only the current and longest continuous
            // segment, not the full history of all temperature observations.
            val thermalAccumulators = listOf(
                DukoMetric.BATTERY_TEMPERATURE,
                DukoMetric.CPU_TEMPERATURE,
                DukoMetric.GPU_TEMPERATURE
            ).associateWith { DukoThermalAccumulator(it, window, cadence) }
            // Two non-overlapping 120-second phases require >=4 minutes.
            // No extra query or polling; short windows remain unassessed.
            val stabilityAccumulators = if (window.durationMs >= 240_000L)
                listOf(DukoMetric.BATTERY_TEMPERATURE,
                    DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE)
                    .associateWith { ThermalStabilityAccumulator(it, window,
                        expectedCadenceMs = cadence) }
                else emptyMap()
            val batteryEnergy = DukoEnergyAccumulator(window, cadence)
            // Absolute MHz bands; no inference about GPU utilization or FPS.
            // Consumes the existing bounded historical cursor, never new polling.
            val gpuResidency = ClockResidencyAccumulator(
                window, listOf(200L, 400L, 600L, 800L, 1000L, 1200L), cadence
            )
            val selectedSql = if (selectedWindow == null) "" else
                " AND elapsed_ms>=? AND elapsed_ms<=?"
            // For a narrow selection the old (session_id,id) index scans the
            // entire recording. The v6 index bounds the scan in elapsed time.
            // ORDER BY id still exposes clock reversals in insertion order;
            // the small selection may require a temporary sort.
            val selectedFrom = if (selectedWindow == null) "samples" else
                "samples INDEXED BY samples_session_elapsed_idx"
            val selectedArgs = if (selectedWindow == null)
                arrayOf(sessionId.toString()) else arrayOf(
                    sessionId.toString(),
                    selectedWindow.startMs.toString(),
                    selectedWindow.endMs.toString()
                )
            database.rawQuery(
                """SELECT elapsed_ms,cpu_load_pct,gpu_load_pct,cpu_clock_mhz,
                          gpu_clock_mhz,battery_temp_c,battery_pct,
                          cpu_temp_c,gpu_temp_c,battery_current_ua,
                          battery_voltage_mv,free_memory_mib,battery_plugged,
                          battery_status,cpu_policy_limits,
                          cpu_policy_hardware_max,cpu_core_load_pct,
                          psi_cpu_some_avg10,psi_memory_some_avg10,
                          psi_memory_full_avg10,cpu_policy_clocks,
                          thermal_status
                   FROM $selectedFrom WHERE session_id=?$selectedSql ORDER BY id""",
                selectedArgs
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue // Legacy row, no valid elapsed clock
                    val t = c.getLong(0)
                    if (t < 0) continue
                    minuteAnalysis.acceptTick(t)
                    val coreLoads = CoreLoadCodec.decode(
                        if (c.isNull(16)) null else c.getString(16)
                    )
                    coreActivity.accept(t, coreLoads)
                    coreLoads.forEach { (id, load) ->
                        coreTimelines.getOrPut(id) {
                            TimedSignalAccumulator("CPU CORE $id", "%", window)
                        }.accept(t, load)
                    }
                    frequencyCaps.accept(t,
                        policyMap(if (c.isNull(14)) null else c.getString(14)),
                        policyMap(if (c.isNull(15)) null else c.getString(15)))
                    policyResidency.accept(t,
                        policyMap(if (c.isNull(20)) null else c.getString(20)))
                    fun signal(index: Int): Double? =
                        if (c.isNull(index)) null else c.getDouble(index)
                            .takeIf { it.isFinite() }
                    timelines["CPU LOAD"]?.accept(t, signal(1))
                    timelines["GPU LOAD"]?.accept(t, signal(2))
                    timelines["CPU CLOCK"]?.accept(t, signal(3))
                    timelines["GPU CLOCK"]?.accept(t, signal(4))
                    timelines["BATTERY TEMP"]?.accept(t, signal(5))
                    timelines["CPU TEMP"]?.accept(t, signal(7))
                    timelines["GPU TEMP"]?.accept(t, signal(8))
                    timelines["CPU PSI SOME AVG10"]?.accept(t, signal(17))
                    timelines["MEMORY PSI SOME AVG10"]?.accept(t, signal(18))
                    timelines["MEMORY PSI FULL AVG10"]?.accept(t, signal(19))
                    minuteAnalysis.acceptPressure(t, signal(17), signal(18), signal(19))
                    eventTimeline.accept(DukoEventSample(
                        elapsedMs = t,
                        cpuLoadPct = signal(1),
                        gpuLoadPct = signal(2),
                        batteryTemperatureC = signal(5),
                        androidThermalStatus = if (c.isNull(21)) null
                            else c.getInt(21),
                        cpuPsiSomeAvg10 = signal(17),
                        memoryPsiSomeAvg10 = signal(18),
                    ))
                    val gpuMhz = if (c.isNull(4)) null else c.getDouble(4)
                        .takeIf { it.isFinite() && it > 0.0 && it <= Long.MAX_VALUE.toDouble() }
                        ?.toLong()?.takeIf { it > 0L }
                    gpuResidency.accept(ClockResidencySample(
                        elapsedMs = t,
                        mhz = gpuMhz,
                        sourceId = sourceForGpu,
                        origin = MeasurementOrigin.UNKNOWN,
                        validity = if (gpuMhz == null) ReadingValidity.UNAVAILABLE
                            else ReadingValidity.VALID,
                    ))
                    val batteryPoint = DukoBatteryPoint(
                        elapsedMs = t,
                        currentMicroAmps = if (c.isNull(9)) null else c.getLong(9),
                        voltageMilliVolts = if (c.isNull(10)) null else c.getInt(10),
                        plugged = if (c.isNull(12)) null else c.getInt(12) != 0,
                        batteryStatus = if (c.isNull(13)) null else when (
                            c.getInt(13)
                        ) {
                            BatteryManager.BATTERY_STATUS_CHARGING ->
                                DukoBatteryStatus.CHARGING
                            BatteryManager.BATTERY_STATUS_DISCHARGING ->
                                DukoBatteryStatus.DISCHARGING
                            BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                                DukoBatteryStatus.NOT_CHARGING
                            BatteryManager.BATTERY_STATUS_FULL ->
                                DukoBatteryStatus.FULL
                            else -> DukoBatteryStatus.UNKNOWN
                        }
                    )
                    batteryEnergy.accept(batteryPoint)
                    minuteAnalysis.acceptBattery(batteryPoint)
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
                        val observation = DukoObservation(
                            t, metric, value, sourceId = src, origin = origin
                        )
                        accumulators.getValue(metric).accept(observation)
                        minuteAnalysis.acceptObservation(observation)
                        thermalAccumulators[metric]?.accept(observation)
                        stabilityAccumulators[metric]?.accept(observation)
                    }
                }
            }
            val reports = accumulators.mapValues { (_, accumulator) ->
                accumulator.finish()
            }
            val outcome = when (session.first) {
                "INTERRUPTED" -> DukoSessionOutcome.INTERRUPTED
                "COMPLETED" -> DukoSessionOutcome.COMPLETED
                else -> DukoSessionOutcome.NEEDS_RECOVERY
            }
            val integrity = DukoSessionIntegrity(outcome, reports)
            val capReport = frequencyCaps.result()
            val energy = batteryEnergy.finish()
            val thermal = thermalAccumulators.mapValues { (_, analyzer) ->
                analyzer.finish()
            }
            DukoFoundationReport(
                sessionId = sessionId,
                window = window,
                integrity = integrity,
                energy = energy,
                thermal = thermal,
                capabilitySnapshotAvailable = snapshotJson != null,
                // The generic Android device capture is never treated as a
                // calibrated electrical instrument.
                confidence = DukoConfidence.assess(integrity, energy, thermal),
                gpuClockResidency = gpuResidency.result(),
                thermalStability = stabilityAccumulators.mapValues { (_, a) -> a.result() },
                coreActivity = coreActivity.result(),
                frequencyCaps = capReport,
                minuteAnalysis = minuteAnalysis.result(),
                cpuPolicyResidency = policyResidency.result(),
                events = eventTimeline.result(
                    caps = capReport,
                    interrupted = outcome != DukoSessionOutcome.COMPLETED,
                    wholeRecording = selectedWindow == null ||
                        (selectedWindow.startMs == recorded.first &&
                            selectedWindow.endMs == recorded.second),
                ),
                timelines = timelines.mapValues { (_, a) -> a.result() },
                perCoreTimelines = coreTimelines.mapValues { (_, a) -> a.result() },
                declaredClusterTuneEnabled = declaredClusterTuneEnabled,
            )
        }
}
