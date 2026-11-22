package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoComparisonTest {
    private val window = DukoWindow(0L, 10_000L)
    private val context = DukoSessionContext(
        game = "Hades", emulator = "Eden", graphicsDriver = "Turnip",
        performanceMode = "Performance", fanProfile = "Smart",
        clusterTuneProfile = "Unknown", clusterTuneEnabled = null,
        origin = MeasurementOrigin.USER_DECLARED
    )
    private fun metric(value: Double, window: DukoWindow = this.window) =
        DukoStatistics.analyze(DukoMetric.CPU_LOAD, window,
            (0..5).map {
                DukoObservation(window.startMs + it * 2_000L,
                    DukoMetric.CPU_LOAD, value,
                    sourceId = "/proc/stat",
                    origin = MeasurementOrigin.VALIDATED_SYSFS)
            })
    private fun input(id: Long, value: Double,
        context: DukoSessionContext = this.context,
        outcome: DukoSessionOutcome = DukoSessionOutcome.COMPLETED,
        window: DukoWindow = this.window,
        stats: DukoMetricAnalysis = metric(value, window),
    ) = DukoComparisonInput(id, window, outcome, context,
        mapOf(DukoMetric.CPU_LOAD to stats))

    @Test fun equalTagsAndSupportedEvidenceProduceDescriptiveDelta() {
        val report = DukoComparison.compare(input(1, 20.0), input(2, 30.0))
        val cpu = report.metrics.getValue(DukoMetric.CPU_LOAD)
        assertEquals(DukoComparisonQuality.COMPARABLE, cpu.quality)
        assertEquals(10.0, cpu.delta!!, 1e-9)
        assertEquals(50.0, cpu.percentChange!!, 1e-9)
        assertEquals(DukoComparisonQuality.COMPARABLE, report.quality)
        assertTrue("TAG_MATCH_DOES_NOT_PROVE_EQUAL_WORKLOAD" in report.contextWarnings)
    }

    @Test fun changedDriverAndUnderclockAreWarningsNotAutomaticAttribution() {
        val changed = context.copy(graphicsDriver = "Gen8 V39",
            clusterTuneEnabled = true, clusterTuneProfile = "Medium")
        val report = DukoComparison.compare(input(1, 20.0),
            input(2, 30.0, changed))
        assertEquals(DukoComparisonQuality.CAUTION, report.quality)
        assertTrue("DRIVER_TAG_DIFFERS" in report.contextWarnings)
        assertTrue("UNDERCLOCK_TAG_DIFFERS" in report.contextWarnings)
        assertNotNull(report.metrics.getValue(DukoMetric.CPU_LOAD).delta)
    }

    @Test fun differentGameOrInterruptedSessionBlocksNumericalComparison() {
        val changed = context.copy(game = "Metroid")
        for (b in listOf(input(2, 30.0, changed),
            input(2, 30.0, outcome = DukoSessionOutcome.INTERRUPTED))) {
            val report = DukoComparison.compare(input(1, 20.0), b)
            assertEquals(DukoComparisonQuality.NOT_COMPARABLE, report.quality)
            assertNull(report.metrics.getValue(DukoMetric.CPU_LOAD).delta)
        }
    }

    @Test fun insufficientEvidenceAndWindowMismatchCannotProduceDelta() {
        val empty = DukoStatistics.analyze(DukoMetric.CPU_LOAD, window,
            emptyList())
        val bad = DukoComparison.compare(input(1, 20.0),
            input(2, 30.0, stats = empty))
        assertNull(bad.metrics.getValue(DukoMetric.CPU_LOAD).delta)
        val otherWindow = DukoWindow(0L, 12_000L)
        val mismatch = DukoComparison.compare(input(1, 20.0),
            input(2, 30.0, stats = metric(30.0, otherWindow)))
        assertNull(mismatch.metrics.getValue(DukoMetric.CPU_LOAD).delta)
    }

    @Test fun zeroBaselineDoesNotInventPercentChange() {
        val cpu = DukoComparison.compare(input(1, 0.0), input(2, 10.0))
            .metrics.getValue(DukoMetric.CPU_LOAD)
        assertEquals(10.0, cpu.delta!!, 1e-9)
        assertNull(cpu.percentChange)
        assertTrue("ZERO_BASELINE_PERCENT_UNDEFINED" in cpu.warnings)
    }

    @Test fun refusesSameSessionAndAllowsPartialMissingMetricsWithoutFabrication() {
        try {
            DukoComparison.compare(input(1, 20.0), input(1, 30.0))
            fail("Same session must be rejected")
        } catch (_: IllegalArgumentException) { }
        val b = input(2, 30.0).copy(metrics = emptyMap())
        val report = DukoComparison.compare(input(1, 20.0), b)
        assertEquals(DukoComparisonQuality.NOT_COMPARABLE, report.quality)
        assertNull(report.metrics.getValue(DukoMetric.CPU_LOAD).delta)
    }
}
