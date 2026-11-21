package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoFrequencyCapEvidenceTest {
    private fun policy(id: String, hardware: Long?, active: Long?) =
        DukoCpuPolicy(id, "0-3", hardware, active)

    @Test fun lowerPolicyCeilingIsEvidenceOfCapNotTunerIdentity() {
        val result = DukoFrequencyCapEvidence.assess(listOf(
            policy("policy0", 1800, 1800),
            policy("policy7", 3200, 2400)
        ))
        assertEquals(DukoFrequencyCapState.OBSERVED_BELOW_HARDWARE_MAX, result.state)
        assertEquals(setOf("policy7"), result.restrictedPolicyIds)
        assertEquals(2, result.comparedPolicyCount)
        assertTrue("POLICY_LIMIT_CAUSE_UNKNOWN" in result.warnings)
        assertTrue("CAP_IS_NOT_PROOF_OF_THROTTLING_OR_CLUSTERTUNE" in result.warnings)
    }

    @Test fun completeStockLimitsDoNotClaimTunerDisabled() {
        val result = DukoFrequencyCapEvidence.assess(listOf(policy("policy0", 1800, 1800)))
        assertEquals(DukoFrequencyCapState.NO_RESTRICTION_OBSERVED, result.state)
        assertEquals(0, result.unknownPolicyCount)
        assertTrue("POLICY_LIMIT_CAUSE_UNKNOWN" in result.warnings)
    }

    @Test fun missingOrUnreadablePolicyNeverMeansStock() {
        val result = DukoFrequencyCapEvidence.assess(listOf(
            policy("policy0", 1800, 1800),
            policy("policy7", null, 2200)
        ))
        assertEquals(DukoFrequencyCapState.INSUFFICIENT_EVIDENCE, result.state)
        assertEquals(1, result.unknownPolicyCount)
        assertTrue("INCOMPLETE_CPU_POLICY_EVIDENCE" in result.warnings)
    }

    @Test fun invalidGreaterThanHardwareIsNotAcceptedAsCap() {
        val result = DukoFrequencyCapEvidence.assess(listOf(policy("policy7", 2400, 3000)))
        assertEquals(DukoFrequencyCapState.INSUFFICIENT_EVIDENCE, result.state)
        assertTrue(result.restrictedPolicyIds.isEmpty())
    }

    @Test fun emptySnapshotIsUnknown() {
        val result = DukoFrequencyCapEvidence.assess(emptyList())
        assertEquals(DukoFrequencyCapState.INSUFFICIENT_EVIDENCE, result.state)
        assertEquals(0, result.comparedPolicyCount)
    }
}
