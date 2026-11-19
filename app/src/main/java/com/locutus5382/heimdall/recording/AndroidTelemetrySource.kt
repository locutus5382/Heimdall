package com.locutus5382.heimdall.recording

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.DeviceSampleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Best-effort, read-only and offline. A source is discovered only through the
 * same SensorCatalog used by Diagnostics; no hardcoded Odin battery Wh or
 * assumed Snapdragon policy0/3/7 topology.
 */
internal class AndroidTelemetrySource(context: Context) : DeviceSampleSource {
    private val app = context.applicationContext
    private val catalog = SensorCatalog.get(app)
    private val manager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activity = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val whitespace = Regex("\\s+")
    private val procStat = File("/proc/stat")
    private var previousCpu: Pair<Long, Long>? = null

    override suspend fun read(): DeviceSample = withContext(Dispatchers.IO) {
        // Each source is isolated: one SELinux denial must not abort the tick.
        val battery = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0)
            (100 * level / scale).coerceIn(0, 100) else null
        val rawTemp = battery?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryTemp = if (rawTemp == Int.MIN_VALUE) null else
            (rawTemp / 10.0).takeIf { it in -30.0..100.0 }

        val voltage = battery?.getIntExtra(
            BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it in 2500..5000 }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            ?.takeIf { it >= 0 }?.let { it != 0 }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val rawCurrent = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()?.toLong()?.takeIf {
            it != Int.MIN_VALUE.toLong() && kotlin.math.abs(it) in 1000L..15_000_000L
        }
        // Android defines positive current for charging and negative for
        // discharging. Validate against observed battery status before storing.
        val current = rawCurrent?.takeIf {
            (plugged == false &&
                status == BatteryManager.BATTERY_STATUS_DISCHARGING && it < 0L) ||
            (plugged == true &&
                status == BatteryManager.BATTERY_STATUS_CHARGING && it > 0L)
        }

        val mem = runCatching {
            ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
                .availMem / (1024L * 1024L)
        }.getOrNull()
        val policyClocks = catalog.clocksMHz()
        val policyLimits = catalog.limitsMHz()
        val hardwareMax = catalog.siliconLimitsMHz()
        val thermal = runCatching { power.currentThermalStatus }.getOrNull()
        val headroom = runCatching { power.getThermalHeadroom(0).toDouble() }
            .getOrNull()?.takeIf { it.isFinite() && it in 0.0..2.0 }
        DeviceSample(
            observedAtMs = System.currentTimeMillis(),
            cpuLoadPercent = cpuPercentOrNull(),
            batteryLevelPercent = percent,
            batteryTemperatureC = batteryTemp,
            availableMemoryMiB = mem,
            cpuClockMHz = policyClocks.values.maxOrNull(),
            gpuClockMHz = catalog.gpuClockMHz(),
            observedElapsedMs = SystemClock.elapsedRealtime(),
            thermalStatus = thermal,
            thermalHeadroom = headroom,
            batteryCurrentUa = current,
            batteryVoltageMv = voltage,
            batteryPlugged = plugged,
            gpuLoadPercent = catalog.gpuUtilization(),
            cpuTemperatureC = catalog.cpuTemperatureC(),
            gpuTemperatureC = catalog.gpuTemperatureC(),
            cpuPolicyClocksMHz = policyClocks,
            cpuPolicyLimitsMHz = policyLimits,
            cpuPolicyHardwareMaxMHz = hardwareMax,
        )
    }

    private fun cpuPercentOrNull(): Double? = runCatching {
        // /proc/stat is restricted on many Android builds. Never substitute
        // the clock/max-clock ratio and label it CPU utilization.
        val line = procStat.useLines { it.first() }
        val fields = line.trim().split(whitespace).drop(1)
        val values = fields.take(8).map(String::toLong)
        if (values.size < 4) return@runCatching null
        val total = values.sum()
        val idle = values[3] + values.getOrElse(4) { 0L }
        val previous = previousCpu
        previousCpu = total to idle
        if (previous == null || total <= previous.first) null
        else (100.0 * (1.0 -
            (idle - previous.second).toDouble() /
            (total - previous.first).toDouble())).coerceIn(0.0, 100.0)
    }.getOrNull()
}
