package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class ThermalStabilityTest {
    private fun row(t: Long, value: Double?, source: String = "battery") =
        DukoObservation(t, DukoMetric.BATTERY_TEMPERATURE, value,
            sourceId = source, origin = MeasurementOrigin.ANDROID_API)

    private fun analyze(rows: List<DukoObservation>): ThermalStabilityResult {
        val a = ThermalStabilityAccumulator(DukoMetric.BATTERY_TEMPERATURE,
            DukoWindow(0L, 360_000L), expectedCadenceMs = 10_000L)
        rows.forEach(a::accept)
        return a.result()
    }

    @Test fun constantTemperatureHasStableLatePhase() {
        val r = analyze((0..36).map { row(it * 10_000L, 35.0) })
        assertEquals(DukoEvidence.SUPPORTED, r.evidence)
        assertEquals(120_000L, r.earlyCoveredMs)
        assertEquals(120_000L, r.lateCoveredMs)
        assertEquals(0.0, r.lateMinusEarlyC!!, 1e-9)
        assertTrue(r.latePlateauObserved)
    }

    @Test fun risingLateTemperatureDoesNotClaimPlateau() {
        val r = analyze((0..36).map { row(it * 10_000L, 30.0 + it * 0.1) })
        assertEquals(DukoEvidence.SUPPORTED, r.evidence)
        assertTrue(r.lateMinusEarlyC!! > 1.0)
        assertFalse(r.latePlateauObserved)
    }

    @Test fun missingGapCannotInventStableHeatSoak() {
        val r = analyze((0..36).map {
            row(it * 10_000L, if (it == 29) null else 35.0)
        })
        assertEquals(DukoEvidence.INSUFFICIENT, r.evidence)
        assertNull(r.lateMinusEarlyC)
        assertFalse(r.latePlateauObserved)
    }

    @Test fun sensorChangeCannotCreateFictitiousTemperatureDelta() {
        val r = analyze((0..36).map {
            row(it * 10_000L, if (it < 20) 32.0 else 44.0,
                if (it < 20) "A" else "B")
        })
        assertEquals(DukoEvidence.INSUFFICIENT, r.evidence)
        assertNull(r.lateMinusEarlyC)
    }

    @Test fun noReadingsYieldInsufficientEvidence() {
        val r = analyze(emptyList())
        assertEquals(DukoEvidence.INSUFFICIENT, r.evidence)
        assertNull(r.lateSlopeCPerMinute)
    }

    @Test fun reversedTimestampCannotQualifyPhase() {
        val rows = (0..36).map { row(it * 10_000L, 35.0) }.toMutableList()
        rows.add(10, row(15_000L, 35.0))
        assertEquals(DukoEvidence.INSUFFICIENT, analyze(rows).evidence)
    }
}
