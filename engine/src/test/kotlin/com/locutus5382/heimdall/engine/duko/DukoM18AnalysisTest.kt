package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoM18AnalysisTest {
    private fun observation(t: Long, metric: DukoMetric, v: Double?) =
        DukoObservation(t, metric, v, sourceId = "fixture",
            origin = MeasurementOrigin.VALIDATED_SYSFS)

    @Test fun oneMinuteIntervalsAreAlignedAndPartialTailIsExplicit() {
        val window = DukoWindow(0L, 150_000L)
        val collector = DukoMinuteAccumulator(window, 2_000L)
        for (t in 0L..150_000L step 2_000L) {
            val v = when {
                t < 60_000L -> 20.0
                t < 120_000L -> 80.0
                else -> 40.0
            }
            collector.acceptTick(t)
            collector.acceptObservation(observation(t, DukoMetric.CPU_LOAD, v))
            collector.acceptPressure(t, 5.0, null, null)
        }
        val report = collector.result()
        assertEquals(3L, report.totalBlocks)
        assertFalse(report.truncated)
        assertEquals(3, report.reportedBlocks)
        assertEquals(60_000L, report.blocks[0].window.durationMs)
        assertEquals(60_000L, report.blocks[1].window.startMs)
        assertEquals(30_000L, report.blocks[2].window.durationMs)
        assertTrue(report.blocks[2].partial)
        assertEquals(20.0, report.blocks[0].metrics.getValue(DukoMetric.CPU_LOAD).median!!, 0.001)
        assertEquals(80.0, report.blocks[1].metrics.getValue(DukoMetric.CPU_LOAD).p95!!, 0.001)
        assertEquals(40.0, report.blocks[2].metrics.getValue(DukoMetric.CPU_LOAD).median!!, 0.001)
        assertEquals(30, report.blocks[0].recordedRows)
        assertEquals(30, report.blocks[1].recordedRows)
        assertEquals(16, report.blocks[2].recordedRows)
        assertEquals(30, report.blocks[0].pressure.getValue("CPU PSI SOME AVG10").samples)
    }

    @Test fun gapMinutesRemainInsufficientAndCannotInheritOtherMinuteSamples() {
        val collector = DukoMinuteAccumulator(DukoWindow(0L, 180_000L), 2_000L)
        collector.acceptTick(0L)
        collector.acceptObservation(observation(0, DukoMetric.CPU_LOAD, 90.0))
        collector.acceptTick(178_000L)
        collector.acceptObservation(observation(178_000L, DukoMetric.CPU_LOAD, 10.0))
        val report = collector.result()
        assertEquals(3L, report.totalBlocks)
        assertEquals(0, report.blocks[1].recordedRows)
        assertEquals(DukoEvidence.INSUFFICIENT,
            report.blocks[1].metrics.getValue(DukoMetric.CPU_LOAD).evidence)
        assertEquals(0.0,
            report.blocks[1].metrics.getValue(DukoMetric.CPU_LOAD).coverage, 0.001)
    }

    @Test fun veryLongRunsExplicitlyReportBoundedFirstMinutes() {
        val report = DukoMinuteAccumulator(
            DukoWindow(1_000L, 1_000L + 420L * 60_000L)
        ).result()
        assertEquals(420L, report.totalBlocks)
        assertTrue(report.truncated)
        assertEquals(DukoMinuteAccumulator.MAX_DISPLAY_BLOCKS, report.reportedBlocks)
        assertEquals(0, report.blocks[240].recordedRows)
    }

    @Test fun cpuResidencyCountsOnlyContinuousValidatedPolicySamples() {
        val a = CpuPolicyResidencyAccumulator(DukoWindow(0L, 10_000L),
            mapOf("policy0" to setOf(0, 1)), 2_000L)
        a.accept(0L, mapOf("policy0" to 1200L))
        a.accept(2000L, mapOf("policy0" to 1200L))
        a.accept(4000L, mapOf("policy0" to 2000L))
        a.accept(6000L, mapOf("policy0" to 2000L))
        a.accept(8000L, emptyMap())
        a.accept(10_000L, mapOf("policy0" to 2800L))
        val r = a.result().single()
        assertEquals("policy0", r.policyId)
        assertEquals(listOf(0, 1), r.relatedCores)
        assertEquals(5, r.observedSamples)
        assertEquals(6_000L, r.residency.coveredMs)
        assertEquals(4_000L, r.residency.unknownMs)
        assertEquals(1, r.residency.transitions)
        assertEquals(800L, r.residency.largestRiseMHz)
        assertEquals(DukoEvidence.INSUFFICIENT, r.residency.evidence)
    }

    @Test fun sustainedLoadAndPsiBecomeEventsOnlyAfterQualifiedDuration() {
        val window = DukoWindow(0L, 150_000L)
        val a = DukoEventAccumulator(window, 2_000L)
        for (t in 0L..150_000L step 2_000L) {
            a.accept(DukoEventSample(t, 98.0, null, 42.5, 2,
                cpuPsiSomeAvg10 = 15.0, memoryPsiSomeAvg10 = null))
        }
        val timeline = a.result()
        assertEquals(1, timeline.events.count { it.type == DukoEventType.CPU_LOAD_HIGH })
        assertEquals(1, timeline.events.count { it.type == DukoEventType.BATTERY_HOT })
        assertEquals(1, timeline.events.count { it.type == DukoEventType.CPU_PSI_PRESSURE })
        assertEquals(1, timeline.events.count { it.type == DukoEventType.THERMAL_PRESSURE })
        assertEquals(2, timeline.events.count {
            it.type == DukoEventType.RECORDING_BEGIN ||
                it.type == DukoEventType.RECORDING_COMPLETE
        })
        assertTrue(timeline.events.zipWithNext().all { (left, right) ->
            left.startMs <= right.startMs
        })
    }

    @Test fun pressureGapsAndMissingSensorsDoNotFabricateEvents() {
        val a = DukoEventAccumulator(DukoWindow(0L, 140_000L), 2_000L)
        for (t in 0L..40_000L step 2_000L) {
            a.accept(DukoEventSample(t, 97.0, null, null, null,
                null, null))
        }
        a.accept(DukoEventSample(130_000L, 97.0, null, null, null, null, null))
        a.accept(DukoEventSample(140_000L, null, null, null, null, null, null))
        val r = a.result(interrupted = true)
        assertFalse(r.events.any { it.type == DukoEventType.CPU_LOAD_HIGH })
        assertTrue(r.events.any { it.type == DukoEventType.RECORDING_INTERRUPTED })
        assertFalse(r.events.any { it.type == DukoEventType.CPU_PSI_PRESSURE })
    }

    @Test fun capTransitionsMergeIntoChronologicalUnifiedTimeline() {
        val w = DukoWindow(0L, 120_000L)
        val caps = FrequencyCapTimeline(w, emptyList(), listOf(
            FrequencyCapEvent("policy0", 2000L, true, 1800L, 2400L),
            FrequencyCapEvent("policy0", 80_000L, false, 2400L, 2400L),
        ))
        val r = DukoEventAccumulator(w).result(caps)
        assertEquals(1, r.events.count { it.type == DukoEventType.POLICY_CAP_OBSERVED })
        assertEquals(1, r.events.count { it.type == DukoEventType.POLICY_CAP_CLEARED })
        assertTrue(r.events.any { it.detail.contains("cause unknown") })
        assertEquals(0, r.droppedEvents)
    }
}
