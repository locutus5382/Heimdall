package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

/** M4a: calculation invariants for user-selected monotonic time windows. */
class DukoWindowSelectionTest {
    private val full = DukoWindow(0L, 10_000L)
    private val rows = (0..5).map { i ->
        DukoObservation(
            elapsedMs = i * 2_000L,
            metric = DukoMetric.CPU_LOAD,
            value = 10.0 + i * 10.0,
            sourceId = "/proc/stat",
            origin = MeasurementOrigin.VALIDATED_SYSFS,
        )
    }
    private val battery = (0..5).map {
        DukoBatteryPoint(
            elapsedMs = it * 2_000L,
            currentMicroAmps = -2_000_000L,
            voltageMilliVolts = 4_000,
            plugged = false,
            batteryStatus = DukoBatteryStatus.DISCHARGING,
        )
    }

    @Test fun fullAndNarrowWindowsHaveIndependentMeansAndSampleCounts() {
        val entire = DukoStatistics.analyze(DukoMetric.CPU_LOAD, full, rows)
        val subWindow = DukoWindow(2_000L, 6_000L)
        val subset = DukoStatistics.analyze(DukoMetric.CPU_LOAD, subWindow, rows)
        assertEquals(35.0, entire.timeWeightedMean!!, 1e-9)
        assertEquals(30.0, subset.timeWeightedMean!!, 1e-9)
        assertEquals(6, entire.validSamples)
        assertEquals(3, subset.validSamples)
        assertEquals(1.0, subset.coverage, 1e-9)
        assertEquals(DukoEvidence.SUPPORTED, subset.evidence)
        assertEquals(subset, DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, subWindow, rows))
    }

    @Test fun selectionBetweenSampleTimestampsDoesNotInterpolateAtEdges() {
        val window = DukoWindow(2_500L, 6_500L)
        val cpu = DukoStatistics.analyze(DukoMetric.CPU_LOAD, window, rows)
        assertEquals(2, cpu.validSamples)
        assertEquals(2_000L, cpu.coveredMs)
        assertEquals(0.5, cpu.coverage, 1e-9)
        assertEquals(35.0, cpu.timeWeightedMean!!, 1e-9)
        assertEquals(DukoEvidence.INSUFFICIENT, cpu.evidence)

        val energy = DukoEnergy.analyze(window, battery)
        assertEquals(2_000L, energy.statistics.coveredMs)
        assertEquals(8.0 * 2.0 / 3_600.0,
            energy.measuredDischargeWh!!, 1e-9)
        assertEquals(DukoEvidence.INSUFFICIENT, energy.evidence)
    }

    @Test fun emptyInteriorWindowIsInsufficientNotZeroUtilization() {
        val selection = DukoWindow(2_100L, 3_900L)
        val cpu = DukoStatistics.analyze(DukoMetric.CPU_LOAD, selection, rows)
        assertEquals(0, cpu.validSamples)
        assertNull(cpu.timeWeightedMean)
        assertNull(cpu.median)
        assertEquals(DukoEvidence.INSUFFICIENT, cpu.evidence)
        assertEquals(0.0, cpu.coverage, 0.0)
        assertTrue("NO_VALID_READINGS" in cpu.warnings)
        assertNull(DukoEnergy.analyze(selection, battery).measuredDischargeWh)
    }

    @Test fun boundaryEndpointsRemainInclusiveWithoutOutsideSamples() {
        val selection = DukoWindow(4_000L, 8_000L)
        val cpu = DukoStatistics.analyze(DukoMetric.CPU_LOAD, selection, rows)
        assertEquals(3, cpu.validSamples)
        assertEquals(30.0, cpu.minimum!!, 1e-9)
        assertEquals(50.0, cpu.maximum!!, 1e-9)
        assertEquals(40.0, cpu.timeWeightedMean!!, 1e-9)
    }
}
