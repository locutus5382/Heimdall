package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoThermalTest {
    private fun temp(
        t: Long, c: Double?, source: String = "battery-temp"
    ) = DukoObservation(
        t, DukoMetric.BATTERY_TEMPERATURE, c,
        sourceId = source, origin = MeasurementOrigin.ANDROID_API
    )

    @Test fun risingTemperatureHasReproducibleSlopeInCelsiusPerMinute() {
        val series = (0..10).map {
            temp(it * 30_000L, 30.0 + it * 0.3)
        }
        val r = DukoThermal.analyze(
            DukoMetric.BATTERY_TEMPERATURE,
            DukoWindow(0, 300_000), series,
            expectedCadenceMs = 30_000L
        )
        assertEquals(0.6, r.warmingSlopeCPerMinute!!, 1e-9)
        assertEquals(3.0, r.temperatureChangeC!!, 1e-9)
        assertEquals(DukoEvidence.SUPPORTED, r.evidence)
        assertEquals(300_000L, r.continuousSegmentMs)
    }

    @Test fun shortRunsMayHaveDescriptiveTemperaturesButNotTrends() {
        val series = (0..10).map { temp(it * 2_000L, 32.0 + it * 0.1) }
        val r = DukoThermal.analyze(
            DukoMetric.BATTERY_TEMPERATURE,
            DukoWindow(0, 20_000), series
        )
        assertNotNull(r.statistics.median)
        assertNull(r.warmingSlopeCPerMinute)
        assertEquals(DukoEvidence.INSUFFICIENT, r.evidence)
        assertTrue("THERMAL_TREND_TOO_SHORT" in r.warnings)
    }

    @Test fun missingTemperatureBreaksRegressionWindow() {
        val series = (0..4).map { temp(it * 30_000L, 32.0) } +
            listOf(temp(150_000, null)) +
            (6..10).map { temp(it * 30_000L, 80.0) }
        val r = DukoThermal.analyze(
            DukoMetric.BATTERY_TEMPERATURE,
            DukoWindow(0, 300_000), series,
            expectedCadenceMs = 30_000L
        )
        assertNull(r.warmingSlopeCPerMinute)
        assertEquals(120_000L, r.continuousSegmentMs)
        assertEquals(DukoEvidence.INSUFFICIENT, r.evidence)
        assertTrue("MISSING_OR_INVALID_VALUES" in r.warnings)
    }

    @Test fun sensorChangesNeverJoinSeparateThermalBaselines() {
        val readings = (0..5).map { temp(it * 30_000L, 33.0, "A") } +
            (6..10).map { temp(it * 30_000L, 42.0, "B") }
        val r = DukoThermal.analyze(
            DukoMetric.BATTERY_TEMPERATURE,
            DukoWindow(0, 300_000), readings,
            expectedCadenceMs = 30_000L
        )
        assertEquals(150_000L, r.continuousSegmentMs)
        assertNotNull(r.warmingSlopeCPerMinute)
        assertEquals(DukoEvidence.QUALIFIED, r.evidence)
        assertTrue("SENSOR_SOURCE_CHANGED" in r.warnings)
    }

    @Test fun missingHardwareThermalDataDoesNotMakeUpSlope() {
        val r = DukoThermal.analyze(
            DukoMetric.GPU_TEMPERATURE,
            DukoWindow(0, 300_000), emptyList(),
            expectedCadenceMs = 30_000L
        )
        assertNull(r.temperatureChangeC)
        assertNull(r.warmingSlopeCPerMinute)
        assertEquals(DukoEvidence.INSUFFICIENT, r.evidence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonThermalMetrics() {
        DukoThermal.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 300_000), emptyList()
        )
    }
}
