package com.locutus5382.heimdall.recording

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.locutus5382.heimdall.engine.DukoCpuPolicySpec
import com.locutus5382.heimdall.engine.DukoDeviceProfile
import com.locutus5382.heimdall.engine.DukoMetric
import com.locutus5382.heimdall.engine.DukoSensorSpec
import com.locutus5382.heimdall.engine.DukoSetting
import com.locutus5382.heimdall.engine.DukoSettingOrigin
import com.locutus5382.heimdall.engine.DukoSource
import com.locutus5382.heimdall.engine.DukoSourceKind
import com.locutus5382.heimdall.engine.DukoUnit
import com.locutus5382.heimdall.engine.DukoAvailability

/**
 * Read-only snapshot captured at session start. Does not request root,
 * interact with ClusterTune, control fan clocks or change performance modes.
 */
internal class DukoDeviceProbe(private val context: Context) {
    private val app = context.applicationContext
    private val sensors = SensorCatalog.get(app)

    fun capture(): DukoDeviceProfile {
        val profiles = sensors.cpuPolicies.map { policy ->
            DukoCpuPolicySpec(
                policy.id, policy.cores,
                sensors.siliconLimitsMHz()[policy.id],
                sensors.limitsMHz()[policy.id],
                sensors.clocksMHz()[policy.id]
            )
        }
        val collected = ArrayList<DukoSensorSpec>()
        fun sensor(
            metric: DukoMetric,
            name: String,
            kind: DukoSourceKind,
            location: String? = null,
            available: Boolean = true,
        ) {
            collected += DukoSensorSpec(metric, DukoSource(
                id = name, kind = kind, unit = metric.unit,
                location = location,
                availability = if (available) DukoAvailability.VALID
                else DukoAvailability.UNSUPPORTED
            ))
        }
        sensor(DukoMetric.CPU_CLOCK, "cpu:cpufreq",
            DukoSourceKind.READ_ONLY_KERNEL,
            "/sys/devices/system/cpu/cpufreq", profiles.any { it.currentClockMHz != null })
        sensor(DukoMetric.GPU_CLOCK, "gpu:devfreq",
            DukoSourceKind.READ_ONLY_KERNEL,
            sensors.gpuClockNodes.firstOrNull()?.absolutePath,
            sensors.gpuClockMHz() != null)
        sensor(DukoMetric.GPU_UTILIZATION, "gpu:busy",
            DukoSourceKind.READ_ONLY_KERNEL,
            sensors.gpuBusyNodes.firstOrNull()?.absolutePath,
            sensors.gpuUtilization() != null)
        sensor(DukoMetric.CPU_TEMPERATURE, "thermal:cpu",
            DukoSourceKind.READ_ONLY_KERNEL,
            sensors.cpuTemperatureNode?.file?.absolutePath,
            sensors.cpuTemperatureC() != null)
        sensor(DukoMetric.GPU_TEMPERATURE, "thermal:gpu",
            DukoSourceKind.READ_ONLY_KERNEL,
            sensors.gpuTemperatureNode?.file?.absolutePath,
            sensors.gpuTemperatureC() != null)
        // These are public APIs, but value availability is evaluated again
        // per sample; they are never a guarantee that useful readings exist.
        sensor(DukoMetric.BATTERY_LEVEL, "android:battery-level",
            DukoSourceKind.ANDROID_PUBLIC_API)
        sensor(DukoMetric.BATTERY_CURRENT, "android:battery-current",
            DukoSourceKind.ANDROID_PUBLIC_API)
        sensor(DukoMetric.BATTERY_VOLTAGE, "android:battery-voltage",
            DukoSourceKind.ANDROID_PUBLIC_API)
        sensor(DukoMetric.BATTERY_TEMPERATURE, "android:battery-temperature",
            DukoSourceKind.ANDROID_PUBLIC_API)
        sensor(DukoMetric.FREE_MEMORY, "android:memory-info",
            DukoSourceKind.ANDROID_PUBLIC_API)
        sensor(DukoMetric.THERMAL_HEADROOM, "android:thermal-headroom",
            DukoSourceKind.ANDROID_PUBLIC_API)
        val isOdin = Build.MANUFACTURER.contains("ayn", ignoreCase = true) ||
            Build.MODEL.contains("odin", ignoreCase = true)
        // Only the raw fan_mode is read; assigning a human-friendly label to
        // unknown OEM integers would falsely claim a tested mode mapping.
        val fan = if (isOdin) runCatching {
            Settings.System.getString(app.contentResolver, "fan_mode")
                ?.trim()?.takeIf { it.matches(Regex("[0-9]{1,3}")) }
                ?.let { DukoSetting("OEM fan_mode=$it (raw)",
                    DukoSettingOrigin.VERIFIED_DEVICE_SETTING) }
        }.getOrNull() else null

        return DukoDeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = sensors.soc.takeUnless { it == "unavailable" },
            androidApi = Build.VERSION.SDK_INT,
            androidBuild = Build.FINGERPRINT,
            kernelRelease = System.getProperty("os.version"),
            totalMemoryMiB = sensors.totalMemoryMiB,
            cpuPolicies = profiles,
            sensors = collected,
            observedOemFanSetting = fan
        )
    }

    /**
     * Presence says nothing about whether a profile is active. ClusterTune
     * provides no documented public profile-state API for third-party apps.
     */
    fun isClusterTuneInstalled(): Boolean = runCatching {
        app.packageManager.getPackageInfo("com.aure.clustertune", 0)
        true
    }.getOrDefault(false)
}
