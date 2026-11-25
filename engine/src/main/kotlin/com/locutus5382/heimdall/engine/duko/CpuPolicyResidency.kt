package com.locutus5382.heimdall.engine.duko

data class CpuPolicyResidencyReport(
    val policyId: String,
    val relatedCores: List<Int>,
    val observedSamples: Int,
    val residency: ClockResidencyResult,
)

/**
 * M1.8 C: clock-frequency residency for each actually observed CPU policy.
 * Policy identity comes from recorded sysfs policy names; topology comes
 * exclusively from the saved device probe, never inferred from policy0/3/7.
 *
 * Durations are sample-hold approximations over adjacent, valid readings.
 * Missing readings and oversized gaps are UNKNOWN, not zero-frequency time.
 */
class CpuPolicyResidencyAccumulator(
    private val window: DukoWindow,
    private val policyCores: Map<String, Set<Int>> = emptyMap(),
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    companion object {
        val BANDS_MHZ: List<Long> = (400L..4_000L step 400L).toList()
        const val MAX_POLICIES = 32
    }
    private class PolicyState(
        val engine: ClockResidencyAccumulator,
        var samples: Int = 0,
    )
    private val states = sortedMapOf<String, PolicyState>()
    private val cadence = expectedCadenceMs
    private val policyRegex = Regex("policy[0-9]{1,3}")

    fun accept(elapsedMs: Long, clockMHz: Map<String, Long>) {
        if (elapsedMs !in window.startMs..window.endMs) return
        val validClocks = clockMHz.filter { (id, freq) ->
            policyRegex.matches(id) && freq in 1L..12_000L
        }
        // Feed missing ticks to previously visible policies to prevent
        // residency from spanning a temporarily unreadable policy.
        val ids = (states.keys + validClocks.keys).distinct().sorted().take(MAX_POLICIES)
        for (id in ids) {
            val state = states[id] ?: PolicyState(
                ClockResidencyAccumulator(window, BANDS_MHZ, cadence)
            ).also { states[id] = it }
            val mhz = validClocks[id]
            if (mhz != null) state.samples++
            state.engine.accept(
                ClockResidencySample(
                    elapsedMs = elapsedMs,
                    mhz = mhz,
                    sourceId = "cpufreq/$id",
                    origin = MeasurementOrigin.VALIDATED_SYSFS,
                    validity = if (mhz != null) ReadingValidity.VALID
                        else ReadingValidity.UNAVAILABLE,
                )
            )
        }
    }

    fun result(): List<CpuPolicyResidencyReport> =
        states.map { (policy, state) ->
            CpuPolicyResidencyReport(
                policyId = policy,
                relatedCores = policyCores[policy].orEmpty().sorted(),
                observedSamples = state.samples,
                residency = state.engine.result()
            )
        }
}
