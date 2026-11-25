package com.locutus5382.heimdall.recording

import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import com.locutus5382.heimdall.engine.duko.CpuStatParser
import com.locutus5382.heimdall.engine.duko.PsiParser

/**
 * User-requested, read-only capability and persistence snapshot.
 *
 * No root, INTERNET, unique hardware identifiers, app/package inventory,
 * game names or background uploads. Results are shown locally and can
 * optionally be exported by the user through the system file picker.
 */
data class DiagnosticSnapshot(
    val capturedAtMs: Long,
    val lines: List<Pair<String, String>>
) {
    fun asText(): String = buildString {
        appendLine("HEIMDALL / LOCAL DIAGNOSTICS")
        appendLine(DateFormat.getDateTimeInstance().format(Date(capturedAtMs)))
        appendLine("Read-only capability probe. Unavailable is not zero.")
        appendLine()
        lines.forEach { (label, value) -> appendLine("$label: $value") }
        appendLine()
        appendLine("No FPS/root/network or hardware changes were performed.")
    }
}

class RuntimeDiagnostics(context: Context) {
    private val app = context.applicationContext

    suspend fun capture(): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        val lines = mutableListOf<Pair<String, String>>()
        fun line(label: String, value: Any?) {
            lines += label to (value?.toString() ?: "unavailable")
        }
        fun readable(path: String): Boolean = runCatching {
            File(path).bufferedReader().use { it.readLine() != null }
        }.getOrDefault(false)

        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("CPU architecture", Build.SUPPORTED_ABIS.firstOrNull())
        line("Overlay permission", Settings.canDrawOverlays(app))
        line("Notifications allowed", runCatching {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .areNotificationsEnabled()
        }.getOrNull())
        line("Thermal status", runCatching {
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        }.getOrNull())

        line("/proc/stat readable", readable("/proc/stat"))
        val coreSource = runCatching {
            File("/proc/stat").useLines { lines ->
                CpuStatParser.parse(lines.take(129).joinToString("\n"))
            }
        }.getOrNull()
        line("CPU cores exposed by /proc/stat", coreSource?.cores?.size)
        line("CPU pressure readable", readable("/proc/pressure/cpu"))
        line("Memory pressure readable", readable("/proc/pressure/memory"))
        line("CPU PSI some avg10 (%)", runCatching {
            PsiParser.parse(File("/proc/pressure/cpu").readText().take(1000))
                .some?.avg10
        }.getOrNull())
        line("Memory PSI some avg10 (%)", runCatching {
            PsiParser.parse(File("/proc/pressure/memory").readText().take(1000))
                .some?.avg10
        }.getOrNull())
        line("Memory PSI full avg10 (%)", runCatching {
            PsiParser.parse(File("/proc/pressure/memory").readText().take(1000))
                .full?.avg10
        }.getOrNull())

        // Exactly the same discovered handles used by AndroidTelemetrySource.
        val catalog = SensorCatalog.get(app)
        line("SoC", catalog.soc)
        line("Total RAM MiB", catalog.totalMemoryMiB)
        line("CPU policies discovered", catalog.cpuPolicies.size)
        line("CPU clock paths readable", catalog.clocksMHz().size)
        line("CPU policy topology", catalog.cpuPolicies.joinToString("; ") {
            "${it.id} (cores ${it.cores})"
        }.ifBlank { "unavailable" })
        line("CPU current MHz", catalog.clocksMHz().entries.joinToString {
            "${it.key}=${it.value}"
        }.ifEmpty { "unavailable" })
        line("CPU policy max MHz", catalog.limitsMHz().entries.joinToString {
            "${it.key}=${it.value}"
        }.ifEmpty { "unavailable" })
        line("CPU hardware max MHz", catalog.siliconLimitsMHz().entries.joinToString {
            "${it.key}=${it.value}"
        }.ifEmpty { "unavailable" })
        line("GPU clock path", catalog.gpuClockNodes.firstOrNull()?.absolutePath)
        line("GPU clock MHz", catalog.gpuClockMHz())
        line("GPU utilization source", catalog.gpuBusyNodes.firstOrNull()?.absolutePath)
        line("GPU utilization %", catalog.gpuUtilization())
        line("Readable thermal zones", catalog.thermalZones.size)
        line("CPU temperature source", catalog.cpuTemperatureNode?.type)
        line("GPU temperature source", catalog.gpuTemperatureNode?.type)
        line("CPU temperature C", catalog.cpuTemperatureC())
        line("GPU temperature C", catalog.gpuTemperatureC())
        line("Battery rated Wh", "not provided by public API")

        // M2.2/K: the observer records timings during EXISTING real samples.
        // Read the bounded snapshot only on explicit Diagnostics inspection.
        val overhead = RecorderOverheadHub.monitor.snapshot()
        line("K / overhead trace", if (overhead == null)
            "unavailable — record a session, then refresh Diagnostics"
            else "local process; upper-bin quantiles, not exact percentiles")
        if (overhead != null) {
            fun latency(value: Long?): String = when (value) {
                null -> "unavailable"
                Long.MAX_VALUE -> ">30,000 ms"
                else -> "≤" + value + " ms"
            }
            line("Recorder trace session", overhead.sessionId)
            line("Recorder trace state", if (overhead.completed)
                "closed (last observed session)" else "recording")
            line("Recorder configured cadence ms", overhead.intervalMs)
            line("Persisted ticks observed", overhead.ticks)
            line("Sensor read failures observed", overhead.failedReads)
            line("SQLite append failures observed", overhead.failedAppends)
            line("Late tick starts observed", overhead.lateTicks)
            line("Missed deadlines observed", overhead.missedDeadlines)
            line("Read latency p50/p95 upper bins",
                latency(overhead.readP50UpperMs) + " / " +
                    latency(overhead.readP95UpperMs))
            line("SQLite append p50/p95 upper bins",
                latency(overhead.appendP50UpperMs) + " / " +
                    latency(overhead.appendP95UpperMs))
            line("End-to-end tick p50/p95 upper bins",
                latency(overhead.totalP50UpperMs) + " / " +
                    latency(overhead.totalP95UpperMs))
            line("Longest single sensor read ms", overhead.maxReadMs)
            line("Longest single SQLite append ms", overhead.maxAppendMs)
            line("Longest end-to-end tick ms", overhead.maxTotalMs)
            line("Maximum deadline lateness ms", overhead.maxDeadlineLatenessMs)
            line("Process CPU consumed within observed tick windows ms",
                overhead.processCpuWindowMs)
            line("CPU attribution caveat",
                "process CPU across all app threads during ticks; NOT " +
                    "isolated recorder CPU, game CPU, or full recording CPU overhead")
        }
        // Expensive memory diagnostics are intentionally ON DEMAND, not in
        // the 2-second sensor path or HUD frame render.
        line("Heimdall process PSS KiB (on demand)",
            runCatching { android.os.Debug.getPss() }.getOrNull())
        line("Heimdall process native heap KiB (on demand)",
            runCatching {
                android.os.Debug.getNativeHeapAllocatedSize() / 1024L
            }.getOrNull())
        line("Process CPU time since launch ms",
            runCatching { android.os.Process.getElapsedCpuTime() }.getOrNull())
        line("Overhead measurement caveat",
            "App PSS/CPU include UI, service, analysis and report rendering; " +
                "compare controlled idle-vs-recording runs on Odin")

        val dbFile = app.getDatabasePath(HeimdallDatabase.NAME)
        line("SQLite database present", dbFile.isFile)
        if (dbFile.isFile) {
            line("SQLite data file KiB", dbFile.length() / 1024L)
            runCatching {
                run {
                    val database = HeimdallDatabase.helper(app).readableDatabase
                    database.rawQuery("PRAGMA journal_mode", null).use { journal ->
                        if (journal.moveToFirst()) line("SQLite journal", journal.getString(0))
                    }
                    line("SQLite schema", database.version)
                    database.rawQuery(
                        "SELECT status, COUNT(*) FROM sessions GROUP BY status",
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            line("Sessions ${cursor.getString(0)}",
                                cursor.getLong(1))
                        }
                    }
                    // No full-table sample scan; this probe must stay cheap.
                    line("SQLite source", "read only")
                }
            }.onFailure { e ->
                line("SQLite inspection error", e.javaClass.simpleName)
            }
        }
        DiagnosticSnapshot(System.currentTimeMillis(), lines)
    }
}
