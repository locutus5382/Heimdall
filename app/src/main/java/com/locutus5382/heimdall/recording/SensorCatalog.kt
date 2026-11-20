package com.locutus5382.heimdall.recording

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * Shared read-only sensor discovery for both sampling and Diagnostics.
 * Unsupported or SELinux-blocked nodes stay absent; no model-specific
 * invented battery capacity, clocks, CPU loads, or temperatures.
 */
internal class SensorCatalog private constructor(private val app: Context) {
    data class CpuPolicy(
        val id: String, val cores: String,
        val clock: File, val policyMax: File, val siliconMax: File,
    )
    data class ThermalNode(val type: String, val file: File)

    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    val soc: String = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
        .filter { it.isNotBlank() && !it.equals("unknown", true) }
        .joinToString(" ").ifBlank { "unavailable" }

    val cpuPolicies: List<CpuPolicy> = runCatching {
        File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.name.matches(Regex("policy[0-9]+")) }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 999 }
            ?.map { dir ->
                CpuPolicy(dir.name,
                    readLine(File(dir, "related_cpus")) ?: "unknown",
                    File(dir, "scaling_cur_freq"),
                    File(dir, "scaling_max_freq"),
                    File(dir, "cpuinfo_max_freq"))
            }.orEmpty()
    }.getOrDefault(emptyList())

    val gpuClockNodes: List<File> = (
        listOf(
            File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"),
            File("/sys/class/kgsl/kgsl-3d0/gpuclk")
        ) + runCatching {
            File("/sys/class/devfreq").listFiles()
                ?.filter { dir ->
                    val n = dir.name.lowercase(Locale.ROOT)
                    n.contains("gpu") || n.contains("mali") ||
                        n.contains("kgsl")
                }
                ?.map { File(it, "cur_freq") }.orEmpty()
        }.getOrDefault(emptyList())
    ).distinctBy { it.absolutePath }.filter { readLine(it) != null }

    // Only percent-formatted direct busy readings; opaque vendor load encodings
    // are not misrepresented as normalized utilization.
    val gpuBusyNodes: List<File> = (
        listOf(
            File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
            File("/sys/class/kgsl/kgsl-3d0/devfreq/load")
        ) + runCatching {
            File("/sys/class/devfreq").listFiles()
                ?.filter { dir ->
                    val n = dir.name.lowercase(Locale.ROOT)
                    n.contains("gpu") || n.contains("mali")
                }?.map { File(it, "load") }.orEmpty()
        }.getOrDefault(emptyList())
    ).distinctBy { it.absolutePath }.filter { parseBusy(readLine(it)) != null }

    val thermalZones: List<ThermalNode> = runCatching {
        discoverThermalNodes(File("/sys/class/thermal").listFiles().orEmpty().toList())
    }.getOrDefault(emptyList())

    val cpuTemperatureNode: ThermalNode? = thermalZones.firstOrNull {
        val n = it.type.lowercase(Locale.ROOT)
        n.contains("cpu") || n.contains("cpuss") ||
            n.contains("big") || n.contains("silver")
    }
    val gpuTemperatureNode: ThermalNode? = thermalZones.firstOrNull {
        val n = it.type.lowercase(Locale.ROOT)
        n.contains("gpu") || n.contains("gpuss")
    }

    val totalMemoryMiB: Long? = runCatching {
        val mem = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(mem)
        mem.totalMem / (1024L * 1024L)
    }.getOrNull()

    fun clocksMHz(): Map<String, Long> =
        cpuPolicies.mapNotNull { policy ->
            clockMHz(policy.clock)?.let { policy.id to it }
        }.toMap()

    fun limitsMHz(): Map<String, Long> =
        cpuPolicies.mapNotNull { policy ->
            clockMHz(policy.policyMax)?.let { policy.id to it }
        }.toMap()

    fun siliconLimitsMHz(): Map<String, Long> =
        cpuPolicies.mapNotNull { policy ->
            clockMHz(policy.siliconMax)?.let { policy.id to it }
        }.toMap()

    fun gpuClockMHz(): Long? = gpuClockNodes.firstNotNullOfOrNull(::gpuMHz)
    fun gpuUtilization(): Double? =
        gpuBusyNodes.firstNotNullOfOrNull { parseBusy(readLine(it)) }
    fun cpuTemperatureC(): Double? = cpuTemperatureNode?.let { temperature(it.file) }
    fun gpuTemperatureC(): Double? = gpuTemperatureNode?.let { temperature(it.file) }

    fun summary(): String = buildString {
        append("device=").append(deviceModel)
        append(";soc=").append(soc)
        append(";policies=").append(cpuPolicies.joinToString("|") {
            "${it.id}[${it.cores}]"
        })
        append(";gpuClock=").append(gpuClockNodes.firstOrNull()?.absolutePath ?: "unavailable")
        append(";gpuBusy=").append(gpuBusyNodes.firstOrNull()?.absolutePath ?: "unavailable")
        append(";cpuTemp=").append(cpuTemperatureNode?.type ?: "unavailable")
        append(";gpuTemp=").append(gpuTemperatureNode?.type ?: "unavailable")
    }

    companion object {
        @Volatile private var cached: SensorCatalog? = null
        fun get(context: Context): SensorCatalog {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: SensorCatalog(context.applicationContext).also { cached = it }
            }
        }

        /**
         * One fresh capability snapshot for every new session. All callers
         * must use Dispatchers.IO: discovery reads vendor sysfs nodes.
         * Diagnostics may reuse the latest snapshot between recordings.
         */
        fun refresh(context: Context): SensorCatalog = synchronized(this) {
            SensorCatalog(context.applicationContext).also { cached = it }
        }

        /**
         * Deterministic thermal discovery. OEMs may expose dozens of zones.
         * Prioritize CPU/GPU nodes before the 24-node read budget so a random
         * directory enumeration does not silently exclude the useful ones.
         * This chooses a stable representative source, not necessarily the
         * hottest sensor; never claim hotspot accuracy without calibration.
         *
         * Package-visible input makes the algorithm testable with temporary
         * directories rather than relying on a particular OEM's sysfs.
         */
        internal fun discoverThermalNodes(directories: List<File>): List<ThermalNode> {
            val thermalPattern = Regex("thermal_zone[0-9]+")
            val candidates = directories
                .filter { it.name.matches(thermalPattern) }
                .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull()
                    ?: Int.MAX_VALUE }
                .mapNotNull { dir ->
                    val type = readLine(File(dir, "type")) ?: return@mapNotNull null
                    ThermalNode(type, File(dir, "temp"))
                }
            fun relevant(type: String): Boolean {
                val t = type.lowercase(Locale.ROOT)
                return t.contains("cpu") || t.contains("cpuss") ||
                    t.contains("big") || t.contains("silver") ||
                    t.contains("gpu") || t.contains("gpuss")
            }
            val prioritized = candidates.filter { relevant(it.type) } +
                candidates.filterNot { relevant(it.type) }
            return prioritized.asSequence()
                .filter { temperature(it.file) != null }
                .take(24)
                .toList()
        }

        private fun readLine(file: File): String? = runCatching {
            file.bufferedReader().use { it.readLine()?.trim() }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        private fun readLong(file: File): Long? =
            readLine(file)?.toLongOrNull()

        private fun clockMHz(path: File): Long? =
            readLong(path)?.takeIf { it in 50_000L..10_000_000L }?.div(1_000L)

        private fun gpuMHz(path: File): Long? {
            val value = readLong(path) ?: return null
            val mhz = when {
                value >= 1_000_000L -> value / 1_000_000L
                value >= 10_000L -> value / 1_000L
                else -> value
            }
            return mhz.takeIf { it in 50L..3_000L }
        }

        private fun parseBusy(text: String?): Double? {
            val n = text?.removeSuffix("%")?.trim()?.toDoubleOrNull()
            return n?.takeIf { it.isFinite() && it in 0.0..100.0 }
        }

        private fun temperature(path: File): Double? {
            val raw = readLine(path)?.toDoubleOrNull() ?: return null
            val c = if (kotlin.math.abs(raw) > 1000.0) raw / 1000.0 else raw
            return c.takeIf { it.isFinite() && it in -30.0..125.0 }
        }
    }
}
