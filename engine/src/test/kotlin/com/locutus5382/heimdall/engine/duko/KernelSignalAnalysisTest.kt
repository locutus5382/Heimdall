package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class KernelSignalAnalysisTest {
    @Test fun realPerCoreJiffyDeltaIsNotClockRatio() {
        val before = CpuStatParser.parse("""
            cpu 30 0 30 140 0 0 0 0
            cpu0 10 0 10 80 0 0 0 0
            cpu1 20 0 20 60 0 0 0 0
        """.trimIndent())
        val after = CpuStatParser.parse("""
            cpu 120 0 100 180 0 0 0 0
            cpu0 40 0 30 130 0 0 0 0
            cpu1 80 0 70 50 0 0 0 0
        """.trimIndent())
        assertEquals(50.0, CpuStatParser.coreLoads(before, after).getValue(0), 0.001)
        // CPU1 counters moved backwards (idle 60 → 50): must reject.
        assertFalse(1 in CpuStatParser.coreLoads(before, after))
        assertNotNull(CpuStatParser.utilization(before.aggregate, after.aggregate))
        assertNull(CpuStatParser.utilization(after.aggregate, before.aggregate))
        assertTrue(CpuStatParser.coreLoads(null, after).isEmpty())
    }

    @Test fun psiParserPreservesSomeFullAndRejectsInvalidValues() {
        val result = PsiParser.parse("""
            some avg10=12.50 avg60=4.20 avg300=1.10 total=99999
            full avg10=0.75 avg60=0.20 avg300=0.10 total=350
        """.trimIndent())
        assertEquals(12.5, result.some!!.avg10, 0.0001)
        assertEquals(0.75, result.full!!.avg10, 0.0001)
        assertEquals(99999L, result.some!!.totalUs)
        assertNull(PsiParser.parse("some avg10=NaN avg60=0 avg300=0 total=1").some)
        assertNull(PsiParser.parse("some avg10=101 avg60=0 avg300=0 total=1").some)
    }

    @Test fun perCoreCodecIgnoresCorruptOrInventedObservations() {
        val raw = CoreLoadCodec.encode(mapOf(7 to 85.0, 0 to 10.5,
            129 to 100.0, 2 to Double.NaN))
        assertEquals("0=10.5|7=85.0", raw)
        assertEquals(mapOf(0 to 10.5, 7 to 85.0), CoreLoadCodec.decode(raw))
        assertEquals(emptyMap<Int, Double>(),
            CoreLoadCodec.decode("0=NaN|1=201|x=50"))
    }

    @Test fun timedSignalShowsRealPeaksAndSampleStddev() {
        val w = DukoWindow(0L, 10_000L)
        val a = TimedSignalAccumulator("CPU LOAD", "%", w)
        a.accept(0L, 10.0)
        a.accept(2_000L, 20.0)
        a.accept(4_000L, 50.0)
        a.accept(6_000L, null)
        a.accept(20_000L, 99.0) // outside selected interval
        val result = a.result()
        assertEquals(3, result.samples)
        assertEquals(10.0, result.minimum!!, 0.00001)
        assertEquals(0L, result.minAtMs)
        assertEquals(50.0, result.maximum!!, 0.00001)
        assertEquals(4_000L, result.maxAtMs)
        assertEquals(26.666666, result.arithmeticMean!!, 0.00001)
        assertEquals(20.81666, result.sampleStandardDeviation!!, 0.0001)
        assertEquals(3, result.trace.size)
    }

    @Test fun longMissingIntervalNeverBecomesConnectedLine() {
        val w = DukoWindow(0L, 120_000L)
        val plot = TimedSignalAccumulator("CPU LOAD", "%", w, bucketCount = 8,
            expectedCadenceMs = 2_000L)
        for (t in 0L..14_000L step 2_000L) plot.accept(t, 90.0)
        // Missing almost one minute: bucket-centre proximity cannot substitute
        // for verified consecutive samples.
        for (t in 75_000L..120_000L step 2_000L) plot.accept(t, 20.0)
        val points = plot.result().trace
        assertTrue(points.size >= 3)
        assertTrue(points.any { !it.continuousFromPrevious && it != points.first() })
        assertEquals(90.0, plot.result().maximum!!, 0.001)
        assertEquals(20.0, plot.result().minimum!!, 0.001)
    }

    @Test fun continuousSensorCadenceConnectsAdjacentObservedBuckets() {
        val w = DukoWindow(0L, 120_000L)
        val plot = TimedSignalAccumulator("GPU LOAD", "%", w, bucketCount = 8,
            expectedCadenceMs = 2_000L)
        for (t in 0L..120_000L step 2_000L) plot.accept(t, 55.0)
        val points = plot.result().trace
        assertEquals(8, points.size)
        assertFalse(points.first().continuousFromPrevious)
        assertTrue(points.drop(1).all { it.continuousFromPrevious })
    }

    @Test fun gapInsideOneBucketBreaksBothSidesOfAveragedPoint() {
        val w = DukoWindow(0L, 80_000L)
        val plot = TimedSignalAccumulator("TEMP", "C", w, bucketCount = 8,
            expectedCadenceMs = 2_000L)
        for (t in listOf(0L, 2_000L, 4_000L, 10_000L, 18_000L, 20_000L,
            22_000L, 24_000L)) plot.accept(t, 37.0)
        val points = plot.result().trace
        // A six-second dropout inside the same bucket invalidates joins
        // even if both neighboring buckets have some valid observations.
        assertTrue(points.any { !it.continuousFromPrevious })
        assertTrue(points.size >= 3)
        assertFalse(points[1].continuousFromPrevious)
    }

    @Test fun coreRankingAndPolicyClustersUseMeasuredIntervalsOnly() {
        val window = DukoWindow(0L, 6_000L)
        val a = CoreActivityAccumulator(window,
            mapOf("policy0" to setOf(0, 1), "policy4" to setOf(4)), 2_000L)
        a.accept(0L, mapOf(0 to 90.0, 1 to 20.0, 4 to 60.0))
        a.accept(2_000L, mapOf(0 to 80.0, 1 to 30.0, 4 to 40.0))
        a.accept(4_000L, mapOf(0 to 70.0, 1 to 40.0, 4 to 20.0))
        a.accept(6_000L, mapOf(0 to 60.0, 1 to 50.0, 4 to 10.0))
        val report = a.result()
        assertEquals(0, report.busiestCore)
        assertEquals("policy0", report.busiestCluster)
        assertEquals(80.0, report.cores.first().averageLoadPercent!!, 0.00001)
        assertEquals(6_000L, report.cores.first().ledObservedMs)
        assertEquals(55.0, report.clusters.first().averageLoadPercent!!, 0.00001)
        assertEquals(1.0, report.coverageFraction, 1e-6)
        assertEquals(setOf(0,1,4), CpuPolicyTopology.parse("0-1 4"))
    }

    @Test fun persistentRestrictedPolicyIsPresetLikeButNotTunerAttribution() {
        val window = DukoWindow(0L, 120_000L)
        val a = FrequencyCapTimelineAccumulator(window)
        for (i in 0..60) {
            a.accept(i * 2_000L, mapOf("policy7" to 2400L),
                mapOf("policy7" to 3200L))
        }
        val p = a.result().policies.single()
        assertTrue(p.presetLike)
        assertEquals(120_000L, p.restrictedMs)
        assertEquals(0, p.transitions)
        assertEquals(0L, p.firstRestrictedAtMs)
        // A cap lasting most but not all of a run is not labelled as a
        // guaranteed preset. Tuner identity is never inferred by this class.
        val b = FrequencyCapTimelineAccumulator(window)
        for (i in 0..60) {
            b.accept(i * 2_000L,
                mapOf("policy7" to if (i < 60) 2400L else 3200L),
                mapOf("policy7" to 3200L))
        }
        assertFalse(b.result().policies.single().presetLike)
        assertEquals(1, b.result().policies.single().transitions)
    }
}
