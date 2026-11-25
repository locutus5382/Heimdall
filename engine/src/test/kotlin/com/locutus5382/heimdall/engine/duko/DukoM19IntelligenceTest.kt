package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoM19IntelligenceTest {
    private fun minutes(
        durationMs: Long,
        cpu: (Long) -> Double?,
        gpu: (Long) -> Double?,
        skip: (Long) -> Boolean = { false },
    ): DukoMinuteReport {
        val w = DukoWindow(0, durationMs)
        val a = DukoMinuteAccumulator(w, 2_000L)
        for (t in 0L..durationMs step 2_000L) {
            if (skip(t)) continue
            a.acceptTick(t)
            for ((metric, value) in listOf(
                DukoMetric.CPU_LOAD to cpu(t),
                DukoMetric.GPU_LOAD to gpu(t),
            )) {
                a.acceptObservation(DukoObservation(t, metric, value,
                    sourceId = if (metric == DukoMetric.CPU_LOAD)
                        "/proc/stat" else "fixture/gpu",
                    origin = MeasurementOrigin.VALIDATED_SYSFS))
            }
        }
        return a.result()
    }

    @Test fun highLoadRequiresTwoAdjacentQualifiedMinutes() {
        val m = minutes(240_000L,
            cpu = { if (it < 120_000L) 78.0 else 10.0 },
            gpu = { if (it < 120_000L) 85.0 else 9.0 })
        val result = DukoIntelligentAnalysis.segment(m)
        assertEquals(listOf(DukoWorkloadPhase.SUSTAINED_LOAD,
            DukoWorkloadPhase.RECOVERY, DukoWorkloadPhase.IDLE),
            result.segments.map { it.phase })
        assertEquals(120_000L, result.segments[0].window.durationMs)
        assertTrue(result.segments[0].warnings.contains(
            "HEURISTIC_60_SECOND_CLASSIFICATION"))
    }

    @Test fun singleHighMinuteIsActiveNotSustained() {
        val m = minutes(120_000L,
            cpu = { if (it < 60_000L) 80.0 else 32.0 },
            gpu = { if (it < 60_000L) 82.0 else 36.0 })
        val segments = DukoIntelligentAnalysis.segment(m).segments
        assertEquals(1, segments.size)
        assertEquals(DukoWorkloadPhase.ACTIVE, segments.single().phase)
    }

    @Test fun missingGpuCannotBeMistakenForIdle() {
        val m = minutes(120_000L, cpu = { 5.0 }, gpu = { null })
        val result = DukoIntelligentAnalysis.segment(m)
        assertEquals(DukoWorkloadPhase.UNKNOWN, result.segments.single().phase)
        assertEquals(DukoEvidence.INSUFFICIENT, result.segments.single().evidence)
        assertTrue(DukoIntelligentAnalysis.suggest(m, result).intervals.isEmpty())
    }

    @Test fun stableThreeMinuteIntervalBecomesQualifiedCandidateWithoutFpsClaim() {
        val m = minutes(180_000L, cpu = { 50.0 }, gpu = { null })
        val phases = DukoIntelligentAnalysis.segment(m)
        val suggestions = DukoIntelligentAnalysis.suggest(m, phases)
        assertEquals(1, suggestions.intervals.size)
        val candidate = suggestions.intervals.single()
        assertEquals(3, candidate.minuteCount)
        assertEquals(180_000L, candidate.window.durationMs)
        assertEquals(DukoEvidence.QUALIFIED, candidate.evidence)
        assertTrue(candidate.warnings.contains("NO_FPS_OR_WORKLOAD_EQUIVALENCE_INFERRED"))
    }

    @Test fun clockGapsAndMissingMinutesNeverCreateComparisonCandidate() {
        val m = minutes(240_000L,
            cpu = { 60.0 }, gpu = { 70.0 },
            skip = { it in 60_000L..180_000L })
        val phases = DukoIntelligentAnalysis.segment(m)
        assertTrue(phases.segments.any { it.phase == DukoWorkloadPhase.UNKNOWN })
        assertTrue(DukoIntelligentAnalysis.suggest(m, phases).intervals.isEmpty())
    }

    @Test fun unstableAdjacentMinuteMeansAreNotRecommended() {
        val m = minutes(180_000L,
            cpu = { if (it < 60_000L) 30.0 else if (it < 120_000L) 63.0 else 31.0 },
            gpu = { null })
        val suggestions = DukoIntelligentAnalysis.suggest(m,
            DukoIntelligentAnalysis.segment(m))
        assertTrue(suggestions.intervals.isEmpty())
    }

    @Test fun conclusionsDescribeLimitsAndNeverAssignClusterTuneOrFpsCause() {
        val w = DukoWindow(0, 180_000L)
        val m = minutes(180_000L, cpu = { 97.0 }, gpu = { 80.0 })
        val caps = FrequencyCapTimeline(w, listOf(
            PolicyCapSummary("policy3", 170_000L, 160_000L, 0L,
                160_000L, 0, true)
        ), emptyList())
        val timeline = DukoEventAccumulator(w).also { a ->
            for (t in 0L..180_000L step 2_000L)
                a.accept(DukoEventSample(t, 97.0, 80.0,
                    null, null, 15.0, null))
        }.result()
        val report = DukoIntelligentAnalysis.analyze(m, timeline, caps,
            emptyMap(), DukoSessionOutcome.COMPLETED)
        assertTrue(report.findings.any { it.kind == DukoFindingKind.POLICY_CEILING })
        assertTrue(report.findings.any { it.kind == DukoFindingKind.SYSTEM_PRESSURE })
        assertTrue(report.findings.all {
            !it.summary.contains("ClusterTune") &&
                !it.summary.contains("FPS") &&
                it.limitation.isNotBlank()
        })
        val interrupted = DukoIntelligentAnalysis.analyze(m, timeline, caps,
            emptyMap(), DukoSessionOutcome.INTERRUPTED)
        assertTrue(interrupted.caveats.contains(
            "SESSION_INTERRUPTED_OR_RECOVERED_CONCLUSIONS_DOWNGRADED"))
        assertTrue(interrupted.findings.none { it.evidence == DukoEvidence.SUPPORTED })
    }

    @Test fun longSessionTruncationIsVisibleInInsightsAndCandidateCaveats() {
        val m = DukoMinuteAccumulator(DukoWindow(0L, 420L * 60_000L)).result()
        val result = DukoIntelligentAnalysis.analyze(m, null, null,
            emptyMap(), DukoSessionOutcome.COMPLETED)
        assertTrue(result.workload.truncated)
        assertEquals(360, result.workload.analyzedMinutes)
        assertTrue(result.caveats.contains(
            "WORKLOAD_PHASES_LIMITED_TO_FIRST_360_MINUTES"))
        assertTrue(result.suggestions.intervals.isEmpty())
    }
}
