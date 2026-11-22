package com.locutus5382.heimdall.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.engine.duko.DukoEvidence
import com.locutus5382.heimdall.engine.duko.DukoMetric
import com.locutus5382.heimdall.engine.duko.ThermalStabilityResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThermalStabilityDisplayTest {
    private fun result(evidence: DukoEvidence, delta: Double?) = ThermalStabilityResult(
        metric = DukoMetric.BATTERY_TEMPERATURE,
        earlyMeanC = if (delta == null) null else 35.0,
        lateMeanC = if (delta == null) null else 35.0 + delta,
        lateMinusEarlyC = delta,
        lateSlopeCPerMinute = if (delta == null) null else 0.04,
        latePlateauObserved = delta != null,
        earlyCoveredMs = 118_000,
        lateCoveredMs = 116_000,
        evidence = evidence,
        warnings = emptyList()
    )

    @Test fun qualifiedPhaseShowsDeltaAndDescriptivePlateau() {
        val lines = thermalStabilityLines(result(DukoEvidence.QUALIFIED, 2.5)).joinToString(" ")
        assertTrue(lines.contains("+2.5 °C"))
        assertTrue(lines.contains("approximate late plateau"))
        assertTrue(lines.contains("QUALIFIED"))
    }

    @Test fun insufficientPhaseNeverDisplaysDeltaEvenIfPresent() {
        val lines = thermalStabilityLines(result(DukoEvidence.INSUFFICIENT, 9.0)).joinToString(" ")
        assertTrue(lines.contains("insufficient"))
        assertFalse(lines.contains("+9.0"))
        assertFalse(lines.contains("plateau"))
    }

    @Test fun absentPhaseDeltaCannotBecomeSupportedDisplay() {
        val lines = thermalStabilityLines(result(DukoEvidence.SUPPORTED, null)).joinToString(" ")
        assertTrue(lines.contains("insufficient"))
        assertFalse(lines.contains("late minus early"))
    }
}
