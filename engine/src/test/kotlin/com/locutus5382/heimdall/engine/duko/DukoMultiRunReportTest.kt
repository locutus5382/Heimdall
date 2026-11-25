package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoMultiRunReportTest {
    private fun sample(
        metric: DukoMetric,
        w: DukoWindow,
        mean: Double,
        evidence: DukoEvidence = DukoEvidence.SUPPORTED,
    ) = DukoMetricAnalysis(
        metric = metric, window = w, observedRows = 61, validSamples = 61,
        invalidOrMissingRows = 0, outOfOrderRows = 0,
        sourceTransitions = 0, coveredMs = w.durationMs,
        longestGapMs = 2_000L, coverage = 1.0,
        minimum = mean, maximum = mean, p5 = mean, median = mean,
        p95 = mean, p99 = mean, sampleMean = mean,
        timeWeightedMean = mean, sampleStandardDeviation = 0.0,
        evidence = evidence, warnings = emptyList()
    )
    private fun input(
        id: Long,
        cpu: Double,
        gpu: Double? = 50.0,
        game: String = "Hades",
        outcome: DukoSessionOutcome = DukoSessionOutcome.COMPLETED,
        start: Long = id * 1_000_000L,
    ): DukoComparisonInput {
        val w = DukoWindow(start, start + 120_000L)
        val metrics = mutableMapOf(DukoMetric.CPU_LOAD to sample(DukoMetric.CPU_LOAD, w, cpu))
        if (gpu != null)
            metrics[DukoMetric.GPU_LOAD] = sample(DukoMetric.GPU_LOAD, w, gpu)
        return DukoComparisonInput(
            sessionId = id, window = w, outcome = outcome,
            context = DukoSessionContext(game = game, emulator = "Eden",
                graphicsDriver = "Turnip", performanceMode = "High",
                fanProfile = "Sport", clusterTuneEnabled = false),
            metrics = metrics
        )
    }

    @Test fun twoRunComparisonUsesExistingAbGatesAndDoesNotInventATrend() {
        val r = DukoMultiRun.compare(listOf(input(1, 40.0), input(2, 55.0)))
        assertEquals(2, r.sessionCount)
        assertEquals(1, r.qualifiedPairs)
        assertEquals(1, r.comparisons.size)
        val pattern = r.metricPatterns.getValue(DukoMetric.CPU_LOAD)
        assertEquals(1, pattern.comparableCount)
        assertEquals(listOf(15.0), pattern.deltas)
        assertEquals(DukoMultiDirection.INSUFFICIENT, pattern.direction)
        assertEquals(15.0, pattern.medianDelta!!, 1e-6)
    }

    @Test fun threeMatchedRunsShowNumericalDirectionWithoutWinners() {
        val r = DukoMultiRun.compare(listOf(
            input(1, 40.0), input(2, 50.0), input(3, 52.0)))
        val p = r.metricPatterns.getValue(DukoMetric.CPU_LOAD)
        assertEquals(2, p.comparableCount)
        assertEquals(DukoMultiDirection.INCREASE, p.direction)
        assertEquals(11.0, p.medianDelta!!, 1e-6)
        assertEquals(10.0, p.minDelta!!, 1e-6)
        assertEquals(12.0, p.maxDelta!!, 1e-6)
        assertTrue(r.warnings.contains("NO_FPS_OR_GAME_PERFORMANCE_WINNER"))
        assertEquals(2, r.qualifiedPairs)
    }

    @Test fun missingMetricNeverBecomesZeroAndBlockedRunIsExcluded() {
        val r = DukoMultiRun.compare(listOf(
            input(1, 40.0, 20.0),
            input(2, 44.0, null),
            input(3, 49.0, 35.0),
        ))
        val gpu = r.metricPatterns.getValue(DukoMetric.GPU_LOAD)
        assertEquals(2, gpu.candidateCount)
        assertEquals(1, gpu.comparableCount)
        assertNull(gpu.deltas[0])
        assertEquals(DukoMultiDirection.INSUFFICIENT, gpu.direction)
        assertTrue(gpu.warnings.contains("UNCOMPARABLE_RUNS_EXCLUDED"))
    }

    @Test fun differentWorkloadsAndInterruptedRunsCannotCreateDeltas() {
        val r = DukoMultiRun.compare(listOf(
            input(1, 40.0),
            input(2, 90.0, game = "Mario Kart"),
            input(3, 80.0, outcome = DukoSessionOutcome.INTERRUPTED),
        ))
        assertEquals(0, r.qualifiedPairs)
        val p = r.metricPatterns.getValue(DukoMetric.CPU_LOAD)
        assertEquals(0, p.comparableCount)
        assertTrue(p.deltas.all { it == null })
        assertEquals(DukoMultiDirection.INSUFFICIENT, p.direction)
        assertTrue(r.comparisons.all {
            it.quality == DukoComparisonQuality.NOT_COMPARABLE
        })
    }

    @Test fun duplicateMissingAndOversizedComparisonsAreRejected() {
        assertTrue(runCatching { DukoMultiRun.compare(listOf(input(1, 40.0))) }.isFailure)
        assertTrue(runCatching {
            DukoMultiRun.compare(listOf(input(1, 40.0), input(1, 50.0)))
        }.isFailure)
        assertTrue(runCatching {
            DukoMultiRun.compare((1L..7L).map { input(it, 40.0) })
        }.isFailure)
    }

    @Test fun visualAbNeverPrintsBlockedNumericalDelta() {
        val a = input(1, 40.0)
        val b = input(2, 99.0, game = "Unrelated workload")
        val comparison = DukoComparison.compare(a, b)
        val document = DukoVisualReport.ab(comparison, a.window, b.window)
        val rows = document.sections.flatMap { it.rows }
        val cpu = rows.first { it.title.startsWith("CPU LOAD") }
        assertTrue(cpu.value.contains("DELTA BLOCKED"))
        assertFalse(cpu.value.contains("B-A 59"))
        assertTrue(document.caveats.any { it.contains("FPS") })
    }

    @Test fun visualMultiListsIndividualSessionWindowsAndLimitations() {
        val r = DukoMultiRun.compare(listOf(
            input(1, 40.0), input(2, 45.0), input(3, 49.0)))
        val document = DukoVisualReport.multi(r)
        assertEquals(1, document.version)
        assertEquals(4, document.sections.size) // scope, patterns and 2 candidates
    }
}
