package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoFoundationTest {
    private fun load(ms: Long, value: Double?, source: String? = "proc") =
        DukoObservation(ms, DukoMetric.CPU_LOAD, value,
            sourceId = source, origin = MeasurementOrigin.VALIDATED_SYSFS)

    @Test fun completeUniformSeriesHasFullCoverageAndCorrectPercentiles() {
        val data = (0..10).map { load(it * 2_000L, it * 10.0) }
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 20_000), data)
        assertEquals(11, a.validSamples)
        assertEquals(20_000L, a.coveredMs)
        assertEquals(1.0, a.coverage, 1e-9)
        assertEquals(50.0, a.median!!, 1e-9)
        assertEquals(100.0, a.p95!!, 1e-9)
        assertEquals(50.0, a.timeWeightedMean!!, 1e-9)
        assertEquals(DukoEvidence.SUPPORTED, a.evidence)
        assertTrue(a.warnings.isEmpty())
    }

    @Test fun irregularIntervalsAreWeightedByActualTime() {
        val data = listOf(load(0, 0.0), load(2_000, 10.0),
            load(4_000, 10.0))
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 4_000), data)
        assertEquals(20.0/3.0, a.sampleMean!!, 1e-8)
        assertEquals(7.5, a.timeWeightedMean!!, 1e-8)
    }

    @Test fun longGapsNeverReceiveInterpolatedCoverage() {
        val data = listOf(load(0, 10.0), load(2_000, 20.0),
            load(12_000, 30.0), load(14_000, 40.0))
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 14_000), data)
        assertEquals(4_000L, a.coveredMs)
        assertEquals(10_000L, a.longestGapMs)
        assertEquals(DukoEvidence.INSUFFICIENT, a.evidence)
        assertTrue("SAMPLING_GAP" in a.warnings)
    }

    @Test fun nullBreaksContinuityEvenWithRegularTimestamps() {
        val data = listOf(load(0, 10.0), load(2_000, null),
            load(4_000, 20.0), load(6_000, 30.0))
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 6_000), data)
        assertEquals(2_000L, a.coveredMs)
        assertEquals(1, a.invalidOrMissingRows)
        assertTrue("MISSING_OR_INVALID_VALUES" in a.warnings)
    }

    @Test fun deniedOrInvalidReadingsCannotBeRelabeledZero() {
        val readings = listOf(
            load(0, Double.NaN),
            DukoObservation(2_000, DukoMetric.CPU_LOAD, null,
                ReadingValidity.PERMISSION_DENIED),
            load(4_000, -40.0),
            load(6_000, 55.0),
        )
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 8_000), readings)
        assertEquals(1, a.validSamples)
        assertEquals(3, a.invalidOrMissingRows)
        assertNull(a.timeWeightedMean)
        assertEquals(DukoEvidence.INSUFFICIENT, a.evidence)
    }

    @Test fun sourceChangePreventsTimeIntegrationAcrossReaders() {
        val data = listOf(
            load(0, 10.0, "sourceA"),
            load(2_000, 20.0, "sourceB"),
            load(4_000, 30.0, "sourceB"),
        )
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 4_000), data)
        assertEquals(2_000L, a.coveredMs)
        assertEquals(1, a.sourceTransitions)
        assertTrue("SENSOR_SOURCE_CHANGED" in a.warnings)
    }

    @Test fun nonMonotonicInputIsFlaggedNotSilentlyReordered() {
        val data = listOf(
            load(0, 10.0), load(4_000, 20.0), load(2_000, 30.0),
            load(6_000, 40.0)
        )
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 6_000), data)
        assertEquals(1, a.outOfOrderRows)
        assertTrue("NON_MONOTONIC_TIMESTAMPS" in a.warnings)
        assertEquals(DukoEvidence.QUALIFIED, a.evidence)
    }

    @Test fun nearestRankPercentileForTwentyValues() {
        val data = (0..20).map { load(it * 1_000L, it.toDouble()) }
        val a = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 20_000), data,
            expectedCadenceMs = 1_000)
        assertEquals(19.0, a.p95!!, 1e-9)
        assertEquals(20.0, a.p99!!, 1e-9)
        assertEquals(10.0, a.median!!, 1e-9)
    }

    @Test fun testTagsAreExplicitAndNotInterpretedAsObservedTuning() {
        val ctx = DukoSessionContext(
            graphicsDriver = "Turnip 27.0.0 R2",
            performanceMode = "High Performance",
            fanProfile = "Sport",
            clusterTuneEnabled = true,
            clusterTuneProfile = "Medium",
            tuningTool = "ClusterTune"
        )
        assertEquals(MeasurementOrigin.USER_DECLARED, ctx.origin)
        assertEquals("Medium", ctx.sanitized().clusterTuneProfile)
        assertEquals("Sport", ctx.fanProfile)
        val restrictions = DukoObservedPolicyLimits(4_000,
            mapOf("policy7" to 2_000L),
            mapOf("policy7" to 3_000L))
        assertTrue("policy7" in restrictions.restrictedPolicies)
        assertEquals("Medium", ctx.clusterTuneProfile) // independent evidence
    }

    @Test fun emptyOrShortWindowsDoNotFakeConfidence() {
        val a = DukoStatistics.analyze(DukoMetric.GPU_LOAD,
            DukoWindow(0, 10_000), emptyList())
        assertEquals(DukoEvidence.INSUFFICIENT, a.evidence)
        assertNull(a.median)
        assertEquals(0.0, a.coverage, 0.0)
        assertEquals(setOf<DukoMetric>(), DukoSessionIntegrity(
            DukoSessionOutcome.INTERRUPTED,
            mapOf(DukoMetric.GPU_LOAD to a)
        ).adequatelyMeasured)
    }
}
