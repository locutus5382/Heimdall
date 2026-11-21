package com.locutus5382.heimdall.engine.duko

/**
 * Read-only interpretation of cpufreq policy ceilings from one device snapshot.
 *
 * A lower scaling_max_freq than cpuinfo_max_freq is evidence of a policy cap,
 * NOT evidence of throttling, ClusterTune, a particular OEM mode, or causation.
 * Unreadable values are unknown, never silently treated as stock.
 */
enum class DukoFrequencyCapState {
    OBSERVED_BELOW_HARDWARE_MAX, NO_RESTRICTION_OBSERVED, INSUFFICIENT_EVIDENCE
}

data class DukoFrequencyCapAssessment(
    val state: DukoFrequencyCapState,
    val restrictedPolicyIds: Set<String>,
    val comparedPolicyCount: Int,
    val unknownPolicyCount: Int,
    val warnings: Set<String>,
)

object DukoFrequencyCapEvidence {
    fun assess(policies: List<DukoCpuPolicy>): DukoFrequencyCapAssessment {
        val restricted = linkedSetOf<String>()
        var compared = 0
        var unknown = 0
        policies.forEach { policy ->
            val hardware = policy.hardwareMaxMHz
            val active = policy.activePolicyMaxMHz
            if (hardware == null || active == null ||
                hardware <= 0L || active <= 0L || active > hardware ||
                policy.policyId.isBlank()
            ) {
                unknown++
            } else {
                compared++
                if (active < hardware) restricted.add(policy.policyId)
            }
        }
        val state = when {
            restricted.isNotEmpty() -> DukoFrequencyCapState.OBSERVED_BELOW_HARDWARE_MAX
            compared == 0 || unknown > 0 -> DukoFrequencyCapState.INSUFFICIENT_EVIDENCE
            else -> DukoFrequencyCapState.NO_RESTRICTION_OBSERVED
        }
        val warnings = buildSet {
            add("POLICY_LIMIT_CAUSE_UNKNOWN")
            if (unknown > 0 || policies.isEmpty()) add("INCOMPLETE_CPU_POLICY_EVIDENCE")
            if (restricted.isNotEmpty()) add("CAP_IS_NOT_PROOF_OF_THROTTLING_OR_CLUSTERTUNE")
        }
        return DukoFrequencyCapAssessment(state, restricted, compared, unknown, warnings)
    }
}
