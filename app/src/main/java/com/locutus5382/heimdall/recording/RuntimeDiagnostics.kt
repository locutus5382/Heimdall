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
        line("CPU pressure readable", readable("/proc/pressure/cpu"))
        line("Memory pressure readable", readable("/proc/pressure/memory"))

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
