package com.locutus5382.heimdall.engine

import org.junit.Assert.*
import org.junit.Test

class DukoFoundationTest {
    private val metric = DukoMetric.CPU_UTILIZATION
    private fun obs(
        time: Long, value: Double?, source: String = "proc:stat"
    ) = DukoObservation(
        metric, time, value, source,
        if (value == null) DukoAvailability.TEMPORARILY_UNREADABLE
        else DukoAvailability.VALID
    )

    @Test fun completeTimelineHasFullCoverageAndCorrectPercentiles() {
        val data = listOf(obs(0, 10.0), obs(2000, 20.0),
            obs(4000, 40.0), obs(6000, 80.0), obs(8000, 100.0))
        val a = DukoStatistics.summarize(metric, data, DukoTimeWindow(0, 10000), 2000)
        assertEquals(5, a.validSampleCount)
        assertEquals(100.0, a.coveragePercent, 0.00001)
        assertEquals(50.0, a.meanOfSamples!!, 0.00001)
        assertEquals(50.0, a.timeWeightedMean!!, 0.00001)
        assertEquals(40.0, a.median!!, 0.00001)
        assertEquals(100.0, a.p95NearestRank!!, 0.00001)
        assertEquals(10000L, a.coveredMs)
        assertEquals(DukoEvidenceLevel.SUPPORTED, a.evidence)
    }

    @Test fun missingRowsNeverBecomeInventedTimeCoverage() {
        val a = DukoStatistics.summarize(metric,
            listOf(obs(0, 10.0), obs(2000, 20.0),
                obs(6000, 60.0), obs(8000, 80.0)),
            DukoTimeWindow(0, 10000), 2000)
        assertEquals(8000L, a.coveredMs)
        assertEquals(80.0, a.coveragePercent, 0.00001)
        assertEquals(1, a.observedGapCount)
        assertEquals(4000L, a.largestObservedGapMs)
        assertEquals(42.5, a.timeWeightedMean!!, 0.00001)
        assertEquals(DukoEvidenceLevel.QUALIFIED, a.evidence)
    }

    @Test fun unavailableSensorIsNotZeroAndEndsPreviousCoverage() {
        val a = DukoStatistics.summarize(metric,
            listOf(obs(0, 0.0), obs(2000, null), obs(4000, 80.0)),
            DukoTimeWindow(0, 6000), 2000)
        assertEquals(2, a.validSampleCount)
        assertEquals(1, a.rejectedSampleCount)
        assertEquals(4000L, a.coveredMs)
        assertEquals(40.0, a.meanOfSamples!!, 0.001)
        assertEquals(0.0, a.min!!, 0.001)
        assertEquals(DukoEvidenceLevel.INSUFFICIENT, a.evidence)
    }

    @Test fun irregularIntervalsUseTimeWeightsNotUnweightedMean() {
        val a = DukoStatistics.summarize(metric,
            listOf(obs(0, 10.0), obs(1000, 50.0), obs(3000, 100.0)),
            DukoTimeWindow(0, 5000), 2000)
        assertEquals(100.0, a.coveragePercent, 0.001)
        assertEquals(62.0, a.timeWeightedMean!!, 0.001)
        assertEquals((10.0 + 50.0 + 100.0) / 3.0,
            a.meanOfSamples!!, 0.001)
    }

    @Test fun duplicateBackwardsAndMixedSourcesAreQualified() {
        val a = DukoStatistics.summarize(metric,
            listOf(obs(2000, 20.0), obs(0, 10.0),
                obs(2000, 99.0), obs(4000, 30.0, "fallback"),
                obs(6000, 40.0)),
            DukoTimeWindow(0, 8000), 2000)
        assertEquals(1, a.duplicateTimestampCount)
        assertEquals(1, a.reversedTimestampCount)
        assertTrue(a.mixedSources)
        assertEquals(DukoEvidenceLevel.QUALIFIED, a.evidence)
        assertEquals(4, a.validSampleCount)
    }

    @Test fun nonFiniteIsRejectedAndNoSignalIsInsufficient() {
        val a = DukoStatistics.summarize(metric,
            listOf(obs(0, Double.NaN), obs(2000, Double.POSITIVE_INFINITY)),
            DukoTimeWindow(0, 4000), 2000)
        assertEquals(0, a.validSampleCount)
        assertNull(a.p95NearestRank)
        assertNull(a.timeWeightedMean)
        assertEquals(0.0, a.coveragePercent, 0.0)
        assertEquals(DukoEvidenceLevel.INSUFFICIENT, a.evidence)
    }

    @Test fun underclockDeclarationAndObservedPolicyAreIndependent() {
        val device = DukoDeviceProfile(
            manufacturer = "Test", model = "Handheld", soc = "Test SoC",
            androidApi = 35, androidBuild = null, kernelRelease = null,
            totalMemoryMiB = null,
            cpuPolicies = listOf(
                DukoCpuPolicySpec("policy0", "0-3", 2200L, 1900L, 1800L),
                DukoCpuPolicySpec("policy4", "4-7", 3200L, 3200L, 2400L)
            ), sensors = emptyList()
        )
        val context = DukoSessionContext(
            sessionId = 7,
            underclockUsed = DukoUnderclockDeclaration.UNKNOWN
        )
        assertEquals(1, device.policiesBelowReportedHardwareMax().size)
        assertEquals(DukoUnderclockDeclaration.UNKNOWN, context.underclockUsed)
        // No "ClusterTune" attribution is inferred from a frequency ceiling.
        assertNull(context.tunerName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun windowCannotHaveZeroDuration() {
        DukoTimeWindow(2000, 2000)
    }
}
