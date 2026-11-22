package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoAbReportExportTest {
    private val a = DukoWindow(1_000L, 11_000L)
    private val b = DukoWindow(50_000L, 60_000L)

    private fun report(
        quality: DukoComparisonQuality,
        delta: Double? = -4.5,
        warnings: List<String> = listOf("DRIVER_TAG_DIFFERS"),
    ) = DukoComparisonReport(
        sessionA = 17L,
        sessionB = 18L,
        quality = quality,
        contextWarnings = warnings,
        metrics = mapOf(DukoMetric.CPU_LOAD to DukoMetricDifference(
            metric = DukoMetric.CPU_LOAD,
            aMean = 42.0,
            bMean = 37.5,
            delta = delta,
            percentChange = -10.7142857,
            quality = quality,
            warnings = listOf("QUALIFIED_METRIC_EVIDENCE"),
        )),
    )

    @Test fun exactWindowsAndEvidenceWarningsSurviveTextAndCsv() {
        val input = report(DukoComparisonQuality.CAUTION)
        val text = DukoAbReportExport.text(input, a, b)
        val csv = DukoAbReportExport.csv(input, a, b)
        assertTrue(text.contains("Session A: 17 | monotonic window: 1000..11000 ms"))
        assertTrue(text.contains("Session B: 18 | monotonic window: 50000..60000 ms"))
        assertTrue(text.contains("DRIVER_TAG_DIFFERS"))
        assertTrue(text.contains("not establish FPS"))
        assertTrue(csv.contains("17,18,1000,11000,50000,60000,CAUTION"))
        assertTrue(csv.contains("-4.500000,-10.714286"))
        assertFalse(csv.contains("'-4.500000"))
        assertTrue(csv.contains("QUALIFIED_METRIC_EVIDENCE"))
        assertEquals(csv, DukoAbReportExport.csv(input, a, b))
    }

    @Test fun blockedComparisonsNeverExportNumericDeltas() {
        val input = report(DukoComparisonQuality.NOT_COMPARABLE)
        val csv = DukoAbReportExport.csv(input, a, b)
        val text = DukoAbReportExport.text(input, a, b)
        assertTrue(csv.contains("42.000000,37.500000,,,NOT_COMPARABLE"))
        assertTrue(text.contains("42.000000 | 37.500000 | N/A | N/A"))
        assertFalse(csv.contains("-4.500000"))
        assertFalse(text.contains("-4.500000"))
    }

    @Test fun missingAndNonFiniteNumbersNeverBecomeInventedResults() {
        val input = report(DukoComparisonQuality.CAUTION, Double.NaN)
        assertFalse(DukoAbReportExport.text(input, a, b).contains("NaN"))
        assertFalse(DukoAbReportExport.csv(input, a, b).contains("NaN"))
    }

    @Test fun csvEscapesUntrustedWarningsAndFormulaPrefixes() {
        val input = report(DukoComparisonQuality.CAUTION,
            warnings = listOf("=HYPERLINK(\"https://example.test\")", "line,break"))
        val csv = DukoAbReportExport.csv(input, a, b)
        assertTrue(csv.contains("'=HYPERLINK"))
        assertTrue(csv.contains("\"\"https://example.test\"\""))
        assertTrue(csv.contains("line,break"))
        assertEquals(2, csv.trimEnd().lines().size)
    }

    @Test fun emptyMetricMapProducesOnlyCsvHeaderAndExplicitTextDisclaimer() {
        val input = DukoComparisonReport(1L, 2L,
            DukoComparisonQuality.NOT_COMPARABLE, emptyList(), emptyMap())
        assertEquals(1, DukoAbReportExport.csv(input, a, b).trimEnd().lines().size)
        assertTrue(DukoAbReportExport.text(input, a, b).contains("no physical", ignoreCase = true))
    }
}
