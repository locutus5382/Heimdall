package com.locutus5382.heimdall.recording

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.DeviceSampleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only, no-root and offline. Unsupported kernel paths return null,
 * never invented zero values. No FPS claims or device control operations.
 */
internal class AndroidTelemetrySource(context: Context) : DeviceSampleSource {
    private val app = context.applicationContext
    private var previousCpu: Pair<Long, Long>? = null

    // Discover CPU policies instead of assuming Odin's 0/3/7 layout.
    // The displayed clock is the maximum *observed readable policy clock*,
    // not a whole-chip mean, CPU percentage or an overclock capability.
    private val policyPaths: List<String> by lazy {
        runCatching {
            File("/sys/devices/system/cpu/cpufreq").listFiles()
                ?.filter { it.name.matches(Regex("policy[0-9]+")) }
                ?.map { "${it.absolutePath}/scaling_cur_freq" }
                .orEmpty()
        }.getOrDefault(emptyList()).ifEmpty {
            listOf("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq")
        }
    }

    // Mali and other GPUs may expose a named devfreq directory. Read-only;
    // paths we cannot identify or access remain unavailable.
    private val gpuPaths: List<String> by lazy {
        val discovered = runCatching {
            File("/sys/class/devfreq").listFiles()
                ?.filter {
                    val name = it.name.lowercase()
                    name.contains("gpu") || name.contains("mali") ||
                        name.contains("kgsl")
                }
                ?.map { "${it.absolutePath}/cur_freq" }
                .orEmpty()
        }.getOrDefault(emptyList())
        listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk"
        ) + discovered
    }

    override suspend fun read(): DeviceSample = withContext(Dispatchers.IO) {
        val battery = app.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0)
            (100.0 * level / scale).toInt().coerceIn(0, 100) else null
        val rawTemp = battery?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryTemp = if (rawTemp != Int.MIN_VALUE) rawTemp / 10.0 else null
        val memory = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(memory)
        DeviceSample(
            observedAtMs = System.currentTimeMillis(),
            cpuLoadPercent = cpuPercentOrNull(),
            batteryLevelPercent = percent,
            batteryTemperatureC = batteryTemp,
            availableMemoryMiB = memory.availMem / (1024L * 1024L),
            cpuClockMHz = policyPaths.mapNotNull(::readKhzAsMhzOrNull)
                .maxOrNull(),
            gpuClockMHz = gpuPaths.firstNotNullOfOrNull(::readHzAsMhzOrNull),
        )
    }

    private fun cpuPercentOrNull(): Double? = runCatching {
        // Android builds can restrict /proc/stat. No permission escalation.
        val line = File("/proc/stat").useLines { lines -> lines.first() }
        val values = line.trim().split(Regex("\\s+")).drop(1)
            .take(8).map(String::toLong)
        if (values.size < 4) return@runCatching null
        val total = values.sum()
        val idle = values[3] + values.getOrElse(4) { 0L }
        val old = previousCpu
        previousCpu = total to idle
        if (old == null || total <= old.first) null
        else (100.0 * (1.0 - (idle - old.second).toDouble() /
            (total - old.first).toDouble())).coerceIn(0.0, 100.0)
    }.getOrNull()

    private fun readKhzAsMhzOrNull(path: String): Long? =
        readPositiveLong(path)?.takeIf { it >= 1_000L }?.div(1000L)

    private fun readHzAsMhzOrNull(path: String): Long? =
        readPositiveLong(path)?.takeIf { it >= 1_000_000L }?.div(1_000_000L)

    private fun readPositiveLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLongOrNull() }
            .getOrNull()?.takeIf { it > 0L }
}
