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
internal class AndroidTelemetrySource(
    context: Context,
    // Test seam: the discovery callback must not execute during construction.
    private val discover: (Context) -> SensorCatalog = { SensorCatalog.get(it) },
) : DeviceSampleSource {
    private val app = context.applicationContext
    // Service.onCreate constructs this object on Main. Sysfs discovery happens
    // only on the first read(), inside withContext(Dispatchers.IO), never here.
    // Cached volatile lookup; refresh at each new session START is visible
    // to subsequent samples without repeating hardware discovery each tick.
    private val catalog: SensorCatalog get() = discover(app)
    private val manager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activity = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val whitespace = Regex("\\s+")
    private val procStat = File("/proc/stat")
    private var previousCpu: Pair<Long, Long>? = null
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val thermalGate = ThermalHeadroomGate()
    private var procStatBlocked = false

    override suspend fun read(): DeviceSample = withContext(Dispatchers.IO) {
        // Each source is isolated: one SELinux denial must not abort the tick.
        val battery = runCatching {
            app.registerReceiver(null, batteryFilter)
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0)
            (100 * level / scale).coerceIn(0, 100) else null
        val rawTemp = battery?.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryTemp = if (rawTemp == Int.MIN_VALUE) null else
            (rawTemp / 10.0).takeIf { it in -30.0..100.0 }

        val rawVoltage = battery?.getIntExtra(
            BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it >= 0 }
        val voltage = BatteryEvidence.acceptedVoltageMv(rawVoltage)
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            ?.takeIf { it >= 0 }?.let { it != 0 }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val rawCurrent = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .toLong()
        }.getOrNull()?.takeUnless { it == Int.MIN_VALUE.toLong() }
        // Preserve raw value even during bypass/plugged discharge or unexpected
        // OEM polarity; never invert sign or claim it is total device input.
        val current = BatteryEvidence.interpretedCurrentUa(rawCurrent, status)
        val chargeCounter = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .toLong()
        }.getOrNull()?.takeUnless { it == Int.MIN_VALUE.toLong() }
        val energyCounter = runCatching {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }.getOrNull()?.takeUnless { it == Long.MIN_VALUE }

        val mem = runCatching {
            activity.getMemoryInfo(memoryInfo)
            memoryInfo.availMem / (1024L * 1024L)
        }.getOrNull()
        val activeCatalog = catalog
        val policyClocks = activeCatalog.clocksMHz()
        val policyLimits = activeCatalog.limitsMHz()
        val hardwareMax = activeCatalog.siliconLimitsMHz()
        val thermal = runCatching { power.currentThermalStatus }.getOrNull()
        val now = SystemClock.elapsedRealtime()
        val headroomQueryAt = if (thermalGate.shouldQuery(now)) now else null
        val headroom = headroomQueryAt?.let {
            runCatching { power.getThermalHeadroom(0).toDouble() }
                .getOrNull()?.takeIf { v -> v.isFinite() && v in 0.0..2.0 }
        }
        DeviceSample(
            observedAtMs = System.currentTimeMillis(),
            cpuLoadPercent = cpuPercentOrNull(),
            batteryLevelPercent = percent,
            batteryTemperatureC = batteryTemp,
            availableMemoryMiB = mem,
            cpuClockMHz = policyClocks.values.maxOrNull(),
            gpuClockMHz = activeCatalog.gpuClockMHz(),
            observedElapsedMs = now,
            thermalStatus = thermal,
            thermalHeadroom = headroom,
            batteryCurrentUa = current,
            batteryVoltageMv = voltage,
            batteryPlugged = plugged,
            gpuLoadPercent = activeCatalog.gpuUtilization(),
            cpuTemperatureC = activeCatalog.cpuTemperatureC(),
            gpuTemperatureC = activeCatalog.gpuTemperatureC(),
            cpuPolicyClocksMHz = policyClocks,
            cpuPolicyLimitsMHz = policyLimits,
            cpuPolicyHardwareMaxMHz = hardwareMax,
            batteryRawCurrentUa = rawCurrent,
            batteryRawVoltageMv = rawVoltage,
            batteryStatus = status?.takeIf { it in 1..5 },
            batteryChargeCounterUah = chargeCounter,
            batteryEnergyCounterNwh = energyCounter,
            thermalHeadroomObservedElapsedMs =
                headroomQueryAt?.takeIf { headroom != null },
        )
    }

    private fun cpuPercentOrNull(): Double? {
        if (procStatBlocked) return null
        return runCatching {
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
    }.onFailure { cause ->
        if (cause is SecurityException ||
            cause is java.io.FileNotFoundException ||
            cause is java.nio.file.AccessDeniedException) procStatBlocked = true
    }.getOrNull()
    }
}
