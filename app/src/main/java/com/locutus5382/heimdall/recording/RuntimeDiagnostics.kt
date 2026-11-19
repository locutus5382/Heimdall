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

        val cpuPolicies = runCatching {
            File("/sys/devices/system/cpu/cpufreq").listFiles()
                ?.filter { it.name.matches(Regex("policy[0-9]+")) }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 999 }
                .orEmpty()
        }.getOrDefault(emptyList())
        line("CPU policies discovered", cpuPolicies.size)
        line("CPU clock paths readable",
            cpuPolicies.count { readable("${it.absolutePath}/scaling_cur_freq") })
        line("CPU policy names",
            cpuPolicies.take(12).joinToString(", ") { it.name }
                .ifEmpty { "unavailable" })

        val kgsl = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk"
        )
        val devfreq = runCatching {
            File("/sys/class/devfreq").listFiles()
                ?.filter {
                    val name = it.name.lowercase()
                    name.contains("gpu") || name.contains("mali") ||
                        name.contains("kgsl")
                }
                ?.map { "${it.absolutePath}/cur_freq" }
                .orEmpty()
        }.getOrDefault(emptyList())
        val gpuPaths = (kgsl + devfreq).distinct()
        line("GPU clock path readable",
            gpuPaths.firstOrNull(::readable) ?: "unavailable")

        val dbFile = app.getDatabasePath("heimdall_v1.db")
        line("SQLite database present", dbFile.isFile)
        if (dbFile.isFile) {
            line("SQLite data file KiB", dbFile.length() / 1024L)
            runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                ).use { database ->
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
