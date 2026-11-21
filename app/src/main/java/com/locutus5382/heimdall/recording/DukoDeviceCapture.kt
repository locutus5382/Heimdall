package com.locutus5382.heimdall.recording

import android.content.Context
import android.os.Build
import com.locutus5382.heimdall.engine.duko.DukoContract
import com.locutus5382.heimdall.engine.duko.DukoCpuPolicy
import com.locutus5382.heimdall.engine.duko.DukoDeviceProfile
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapAssessment
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapEvidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * D1: Capture a non-identifying device capability snapshot at session START.
 * Reading cpufreq limits is evidence of *observed limits*, not evidence of
 * which user/OEM/third-party application set them. No PServer/root required.
 */
internal object DukoDeviceCapture {
    private fun cpuMHz(file: File): Long? = runCatching {
        file.readText().trim().toLongOrNull()
            ?.takeIf { it in 50_000L..10_000_000L }?.div(1000L)
    }.getOrNull()
    private fun shortText(file: File) = runCatching {
        file.readText().trim().take(100).ifEmpty { null }
    }.getOrNull()

    fun probe(context: Context): DukoDeviceProfile {
        // Per-START refresh occurs from LocalRecordingStore.begin() on IO,
        // BEFORE the database transaction, never on Service.onCreate.
        val catalog = SensorCatalog.refresh(context.applicationContext)
        return DukoDeviceProfile(
            capturedAtMs = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            soc = catalog.soc,
            androidSdk = Build.VERSION.SDK_INT,
            androidBuild = Build.DISPLAY.orEmpty().take(120),
            kernel = System.getProperty("os.version").orEmpty().take(120),
            cpuPolicies = catalog.cpuPolicies.map { p ->
                val parent = p.clock.parentFile
                DukoCpuPolicy(
                    policyId = p.id, relatedCpus = p.cores,
                    hardwareMaxMHz = cpuMHz(p.siliconMax),
                    activePolicyMaxMHz = cpuMHz(p.policyMax),
                    activePolicyMinMHz = parent?.let {
                        cpuMHz(File(it, "scaling_min_freq"))
                    },
                    governor = parent?.let {
                        shortText(File(it, "scaling_governor"))
                    },
                )
            },
            totalMemoryMiB = catalog.totalMemoryMiB,
            // Rated battery capacity is intentionally unknown. We must not
            // infer it from reported percentage or a hardcoded Odin size.
            reportedBatteryCapacityWh = null,
            gpuClockSource = catalog.gpuClockNodes.firstOrNull()?.absolutePath,
            gpuLoadSource = catalog.gpuBusyNodes.firstOrNull()?.absolutePath,
            cpuTempSource = catalog.cpuTemperatureNode?.let {
                "${it.type}: ${it.file.absolutePath}"
            },
            gpuTempSource = catalog.gpuTemperatureNode?.let {
                "${it.type}: ${it.file.absolutePath}"
            }
        )
    }

    /** Evaluate persisted START evidence without re-probing or altering raw samples. */
    fun assessSavedCpuPolicyCaps(profileJson: String): DukoFrequencyCapAssessment {
        val array = JSONObject(profileJson).getJSONArray("cpuPolicies")
        val policies = buildList {
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                add(DukoCpuPolicy(
                    policyId = p.optString("id"),
                    relatedCpus = p.optString("relatedCpus"),
                    hardwareMaxMHz = if (p.has("hardwareMaxMHz"))
                        p.optLong("hardwareMaxMHz") else null,
                    activePolicyMaxMHz = if (p.has("policyMaxMHz"))
                        p.optLong("policyMaxMHz") else null,
                ))
            }
        }
        return DukoFrequencyCapEvidence.assess(policies)
    }

    fun encode(profile: DukoDeviceProfile): String {
        val p = JSONObject()
        p.put("contractVersion", DukoContract.VERSION)
        p.put("capturedAtMs", profile.capturedAtMs)
        p.put("manufacturer", profile.manufacturer)
        p.put("model", profile.model)
        p.put("soc", profile.soc)
        p.put("androidSdk", profile.androidSdk)
        p.put("androidBuild", profile.androidBuild)
        p.put("kernel", profile.kernel)
        profile.totalMemoryMiB?.let { p.put("totalMemoryMiB", it) }
        profile.reportedBatteryCapacityWh?.let { p.put("batteryCapacityWh", it) }
        profile.gpuClockSource?.let { p.put("gpuClockSource", it) }
        profile.gpuLoadSource?.let { p.put("gpuLoadSource", it) }
        profile.cpuTempSource?.let { p.put("cpuTempSource", it) }
        profile.gpuTempSource?.let { p.put("gpuTempSource", it) }
        val policies = JSONArray()
        profile.cpuPolicies.forEach { cpu ->
            val j = JSONObject()
                .put("id", cpu.policyId)
                .put("relatedCpus", cpu.relatedCpus)
            cpu.hardwareMaxMHz?.let { j.put("hardwareMaxMHz", it) }
            cpu.activePolicyMaxMHz?.let { j.put("policyMaxMHz", it) }
            cpu.activePolicyMinMHz?.let { j.put("policyMinMHz", it) }
            cpu.governor?.let { j.put("governor", it) }
            policies.put(j)
        }
        p.put("cpuPolicies", policies)
        return p.toString()
    }
}
