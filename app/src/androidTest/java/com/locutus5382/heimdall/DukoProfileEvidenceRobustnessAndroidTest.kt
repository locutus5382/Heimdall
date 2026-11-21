package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapState
import com.locutus5382.heimdall.recording.DukoDeviceCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DukoProfileEvidenceRobustnessAndroidTest {
    @Test fun malformedOrMissingCpuPoliciesAreUnknown() {
        listOf("not-json", "{}", """{"cpuPolicies":null}""").forEach { input ->
            val result = DukoDeviceCapture.assessSavedCpuPolicyCaps(input)
            assertEquals(DukoFrequencyCapState.INSUFFICIENT_EVIDENCE, result.state)
            assertTrue(result.warnings.contains("INCOMPLETE_CPU_POLICY_EVIDENCE"))
        }
    }

    @Test fun malformedEntryCannotBecomeNoRestrictionObserved() {
        val result = DukoDeviceCapture.assessSavedCpuPolicyCaps(
            """{"cpuPolicies":[{"id":"policy0","hardwareMaxMHz":3000,"policyMaxMHz":3000},null,7]}"""
        )
        assertEquals(DukoFrequencyCapState.INSUFFICIENT_EVIDENCE, result.state)
        assertEquals(2, result.unknownPolicyCount)
    }

    @Test fun numericGarbageIsUnknownRatherThanZeroOrStock() {
        val result = DukoDeviceCapture.assessSavedCpuPolicyCaps(
            """{"cpuPolicies":[{"id":"policy0","hardwareMaxMHz":"bad","policyMaxMHz":2000}]}"""
        )
        assertEquals(DukoFrequencyCapState.INSUFFICIENT_EVIDENCE, result.state)
    }

    @Test fun validRestrictedPolicyStillDetected() {
        val result = DukoDeviceCapture.assessSavedCpuPolicyCaps(
            """{"cpuPolicies":[{"id":"policy7","hardwareMaxMHz":3200,"policyMaxMHz":2800}]}"""
        )
        assertEquals(DukoFrequencyCapState.OBSERVED_BELOW_HARDWARE_MAX, result.state)
        assertTrue(result.restrictedPolicyIds.contains("policy7"))
    }
}
