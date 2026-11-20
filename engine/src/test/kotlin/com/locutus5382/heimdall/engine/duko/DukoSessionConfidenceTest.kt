package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

/** M3c: outcome-aware, device-calibration-aware conclusions (pure JVM). */
class DukoSessionConfidenceTest {
    private data class Fixture(
        val integrity: DukoSessionIntegrity,
        val energy: DukoEnergyReport,
        val thermal: Map<DukoMetric, DukoThermalReport>,
    )

    private fun scenario(outcome: DukoSessionOutcome): Fixture {
        val window = DukoWindow(0L, 300_000L)
        val times = (0..10).map { it * 30_000L }
        val cpu = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, window,
            times.map {
                DukoObservation(it, DukoMetric.CPU_LOAD, 40.0,
                    sourceId = "/proc/stat",
                    origin = MeasurementOrigin.VALIDATED_SYSFS)
            },
            expectedCadenceMs = 30_000L,
        )
        val energy = DukoEnergy.analyze(window, times.map {
            DukoBatteryPoint(
                elapsedMs = it,
                currentMicroAmps = -2_000_000L,
                voltageMilliVolts = 4_000,
                plugged = false,
                batteryStatus = DukoBatteryStatus.DISCHARGING,
            )
        }, expectedCadenceMs = 30_000L)
        val trend = DukoThermal.analyze(
            DukoMetric.BATTERY_TEMPERATURE, window,
            times.map {
                DukoObservation(it, DukoMetric.BATTERY_TEMPERATURE, 33.0,
                    sourceId = "Android:Battery",
                    origin = MeasurementOrigin.ANDROID_API)
            },
            expectedCadenceMs = 30_000L,
        )
        assertEquals(DukoEvidence.SUPPORTED, cpu.evidence)
        assertEquals(DukoEvidence.SUPPORTED, energy.evidence)
        assertEquals(DukoEvidence.SUPPORTED, trend.evidence)
        return Fixture(
            DukoSessionIntegrity(outcome, mapOf(DukoMetric.CPU_LOAD to cpu)),
            energy,
            mapOf(DukoMetric.BATTERY_TEMPERATURE to trend),
        )
    }

    @Test fun completeSessionKeepsMeasuredStatisticsButQualifiesUncalibratedEnergy() {
        val f = scenario(DukoSessionOutcome.COMPLETED)
        val report = DukoConfidence.assess(f.integrity, f.energy, f.thermal)
        assertEquals(DukoEvidence.SUPPORTED, report.energyCalculationEvidence)
        assertEquals(DukoEvidence.QUALIFIED, report.batteryTerminalConclusion)
        assertEquals(DukoEvidence.SUPPORTED,
            report.metricConclusions[DukoMetric.CPU_LOAD])
        assertEquals(DukoEvidence.SUPPORTED,
            report.thermalTrendConclusions[DukoMetric.BATTERY_TEMPERATURE])
        assertEquals(DukoBatteryCalibration.NOT_VALIDATED, report.batteryCalibration)
        assertTrue("BATTERY_ELECTRICAL_CALIBRATION_NOT_VALIDATED" in report.warnings)
        assertTrue("BATTERY_TERMINAL_POWER_NOT_GAME_OR_WALL_POWER" in report.warnings)
    }

    @Test fun externalCalibrationCanLiftOnlyTheCalibrationCapNotRawMeasurements() {
        val f = scenario(DukoSessionOutcome.COMPLETED)
        val report = DukoConfidence.assess(f.integrity, f.energy, f.thermal,
            batteryCalibration = DukoBatteryCalibration.INDEPENDENTLY_VALIDATED)
        assertEquals(DukoEvidence.SUPPORTED, report.batteryTerminalConclusion)
        assertFalse("BATTERY_ELECTRICAL_CALIBRATION_NOT_VALIDATED" in report.warnings)
        assertTrue("BATTERY_TERMINAL_POWER_NOT_GAME_OR_WALL_POWER" in report.warnings)
    }

    @Test fun interruptedSessionCapsAllSupportedConclusionsEvenIfCalibrated() {
        val f = scenario(DukoSessionOutcome.INTERRUPTED)
        val report = DukoConfidence.assess(f.integrity, f.energy, f.thermal,
            batteryCalibration = DukoBatteryCalibration.INDEPENDENTLY_VALIDATED)
        assertEquals(DukoEvidence.SUPPORTED, report.energyCalculationEvidence)
        assertEquals(DukoEvidence.QUALIFIED, report.batteryTerminalConclusion)
        assertEquals(DukoEvidence.QUALIFIED,
            report.metricConclusions[DukoMetric.CPU_LOAD])
        assertEquals(DukoEvidence.QUALIFIED,
            report.thermalTrendConclusions[DukoMetric.BATTERY_TEMPERATURE])
        assertTrue("SESSION_INTERRUPTED" in report.warnings)
    }

    @Test fun needsRecoveryMakesAllConclusionsInsufficientWithoutDeletingEvidence() {
        val f = scenario(DukoSessionOutcome.NEEDS_RECOVERY)
        val report = DukoConfidence.assess(f.integrity, f.energy, f.thermal,
            batteryCalibration = DukoBatteryCalibration.INDEPENDENTLY_VALIDATED)
        assertEquals(DukoEvidence.SUPPORTED, report.energyCalculationEvidence)
        assertEquals(DukoEvidence.INSUFFICIENT, report.batteryTerminalConclusion)
        assertEquals(DukoEvidence.INSUFFICIENT,
            report.metricConclusions[DukoMetric.CPU_LOAD])
        assertEquals(DukoEvidence.INSUFFICIENT,
            report.thermalTrendConclusions[DukoMetric.BATTERY_TEMPERATURE])
        assertTrue("SESSION_NEEDS_RECOVERY" in report.warnings)
    }

    @Test fun calibrationCannotUpgradeInsufficientElectricalCoverage() {
        val f = scenario(DukoSessionOutcome.COMPLETED)
        val noCurrent = DukoEnergy.analyze(
            f.energy.window, listOf(
                DukoBatteryPoint(0L, null, 4_000, false,
                    batteryStatus = DukoBatteryStatus.DISCHARGING),
                DukoBatteryPoint(300_000L, null, 4_000, false,
                    batteryStatus = DukoBatteryStatus.DISCHARGING),
            ), expectedCadenceMs = 30_000L,
        )
        assertEquals(DukoEvidence.INSUFFICIENT, noCurrent.evidence)
        val report = DukoConfidence.assess(f.integrity, noCurrent, f.thermal,
            batteryCalibration = DukoBatteryCalibration.INDEPENDENTLY_VALIDATED)
        assertEquals(DukoEvidence.INSUFFICIENT, report.batteryTerminalConclusion)
        assertEquals(DukoEvidence.INSUFFICIENT, report.energyCalculationEvidence)
    }

    @Test fun missingGpuEvidenceDoesNotInvalidateIndependentCpuAndEnergyDomains() {
        val f = scenario(DukoSessionOutcome.COMPLETED)
        val unavailable = DukoStatistics.analyze(
            DukoMetric.GPU_LOAD, f.energy.window, emptyList(),
            expectedCadenceMs = 30_000L,
        )
        val integrity = DukoSessionIntegrity(
            DukoSessionOutcome.COMPLETED,
            f.integrity.metrics + (DukoMetric.GPU_LOAD to unavailable),
        )
        val a = DukoConfidence.assess(integrity, f.energy, f.thermal)
        val b = DukoConfidence.assess(integrity, f.energy, f.thermal)
        assertEquals(a, b)
        assertEquals(DukoEvidence.INSUFFICIENT,
            a.metricConclusions[DukoMetric.GPU_LOAD])
        assertEquals(DukoEvidence.SUPPORTED,
            a.metricConclusions[DukoMetric.CPU_LOAD])
        assertEquals(DukoEvidence.QUALIFIED, a.batteryTerminalConclusion)
    }
}
