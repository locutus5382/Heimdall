package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class ClockResidencyTest {
    private val w = DukoWindow(0, 6000)
    private fun s(t: Long, mhz: Long?, source: String = "policy0") =
        ClockResidencySample(t, mhz, source, MeasurementOrigin.VALIDATED_SYSFS)

    @Test fun fixedClockAccumulatesCoveredTimeAndUnknownBoundary() {
        val a = ClockResidencyAccumulator(w, listOf(1000, 2000))
        listOf(s(0, 1200), s(2000, 1200), s(4000, 1200), s(6000, 1200)).forEach(a::accept)
        val r = a.result()
        assertEquals(listOf(0L, 6000L, 0L), r.bandDurationMs)
        assertEquals(0L, r.unknownMs)
        assertEquals(0, r.transitions)
        assertEquals(DukoEvidence.SUPPORTED, r.evidence)
    }

    @Test fun missingAndSourceChangesNeverBridge() {
        val a = ClockResidencyAccumulator(w, listOf(1000, 2000))
        listOf(s(0, 1200), s(2000, null), s(4000, 1500),
            s(6000, 1500, "policy1")).forEach(a::accept)
        assertEquals(0L, a.result().coveredMs)
        assertEquals(DukoEvidence.INSUFFICIENT, a.result().evidence)
    }

    @Test fun gapAndReversedTimestampNeverFabricateTime() {
        val a = ClockResidencyAccumulator(w, listOf(1000, 2000))
        listOf(s(0, 900), s(5000, 900), s(4000, 900), s(6000, 900)).forEach(a::accept)
        assertEquals(0L, a.result().coveredMs)
    }

    @Test fun oscillationCountsOnlyQualifiedTransitions() {
        val a = ClockResidencyAccumulator(w, listOf(1000, 2000))
        listOf(s(0, 900), s(2000, 1900), s(4000, 900), s(6000, 1900)).forEach(a::accept)
        val r = a.result()
        assertEquals(listOf(4000L, 2000L, 0L), r.bandDurationMs)
        assertEquals(3, r.transitions)
        assertEquals(1000L, r.largestRiseMHz)
        assertEquals(1000L, r.largestDropMHz)
    }

    @Test fun noValuesAndUnverifiedSourceDoNotClaimSupport() {
        val empty = ClockResidencyAccumulator(w, listOf(1000))
        assertEquals(6000L, empty.result().unknownMs)
        val a = ClockResidencyAccumulator(w, listOf(1000))
        listOf(0L, 2000L, 4000L, 6000L).forEach {
            a.accept(ClockResidencySample(it, 900, null, MeasurementOrigin.UNKNOWN))
        }
        assertEquals(DukoEvidence.QUALIFIED, a.result().evidence)
    }

    @Test fun largeStreamKeepsOnlyBandCounters() {
        val a = ClockResidencyAccumulator(DukoWindow(0, 2000000), listOf(1000, 2000))
        for (i in 0..1000) a.accept(s(i * 2000L, 1200))
        assertEquals(2000000L, a.result().coveredMs)
    }
}
