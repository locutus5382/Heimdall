package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

/**
 * M3a: sensor provenance and continuity confidence. These are pure JVM tests:
 * no inferred sensor availability, no physical Odin calibration claims.
 */
class DukoM3ConfidenceTest {
    private fun load(
        ms: Long, value: Double?, source: String? = "sensor-A",
        origin: MeasurementOrigin = MeasurementOrigin.VALIDATED_SYSFS
    ) = DukoObservation(ms, DukoMetric.CPU_LOAD, value,
        sourceId = source, origin = origin)

    @Test fun sourceChangeAcrossMissingReadingIsStillReported() {
        val rows = listOf(
            load(0, 10.0), load(2_000, null),
            load(4_000, 20.0, "sensor-B"),
            load(6_000, 30.0, "sensor-B")
        )
        val result = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 6_000), rows)
        assertEquals(1, result.sourceTransitions)
        assertEquals(2_000L, result.coveredMs)
        assertEquals(DukoEvidence.INSUFFICIENT, result.evidence)
        assertTrue("SENSOR_SOURCE_CHANGED" in result.warnings)
        assertTrue("MISSING_OR_INVALID_VALUES" in result.warnings)
    }

    @Test fun sameSensorLabelButDifferentProvenanceCannotBridge() {
        val rows = listOf(
            load(0, 12.0, origin = MeasurementOrigin.VALIDATED_SYSFS),
            load(2_000, 20.0, origin = MeasurementOrigin.UNKNOWN),
            load(4_000, 28.0, origin = MeasurementOrigin.UNKNOWN)
        )
        val result = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, DukoWindow(0, 4_000), rows)
        assertEquals(1, result.sourceTransitions)
        assertEquals(2_000L, result.coveredMs)
        assertTrue("SENSOR_SOURCE_CHANGED" in result.warnings)
        assertTrue("SOURCE_NOT_VERIFIED" in result.warnings)
    }

    @Test fun anIsolatedMissingTickInHourIsQualifiedNotPerfect() {
        val window = DukoWindow(0, 3_600_000)
        val rows = (0..1_800).map { i ->
            load(i * 2_000L, if (i == 900) null else 42.0)
        }
        val result = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, window, rows)
        assertTrue(result.coverage > DukoStatistics.HIGH_COVERAGE)
        assertEquals(1, result.invalidOrMissingRows)
        assertEquals(DukoEvidence.QUALIFIED, result.evidence)
        assertTrue("MISSING_OR_INVALID_VALUES" in result.warnings)
        // Repeated analysis must be deterministic before introducing M4 cache.
        assertEquals(result, DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, window, rows))
    }

    @Test fun shortGapInLongRecordingMayHaveHighCoverageButIsNotSupported() {
        val window = DukoWindow(0, 3_600_000)
        val rows = (0..1_800).filterNot { it in 500..504 }.map { i ->
            load(i * 2_000L, 35.0)
        }
        val result = DukoStatistics.analyze(
            DukoMetric.CPU_LOAD, window, rows)
        assertTrue(result.coverage > DukoStatistics.HIGH_COVERAGE)
        assertEquals(12_000L, result.longestGapMs)
        assertEquals(DukoEvidence.QUALIFIED, result.evidence)
        assertTrue("SAMPLING_GAP" in result.warnings)
    }

    @Test fun thermalOriginChangeWithSameLabelCannotInventSlope() {
        val rows = (0..20).map { i ->
            DukoObservation(i * 30_000L, DukoMetric.BATTERY_TEMPERATURE,
                if (i <= 10) 33.0 else 53.0,
                sourceId = "battery-temp",
                origin = if (i <= 10) MeasurementOrigin.ANDROID_API
                    else MeasurementOrigin.UNKNOWN)
        }
        val report = DukoThermal.analyze(
            DukoMetric.BATTERY_TEMPERATURE,
            DukoWindow(0, 600_000), rows,
            expectedCadenceMs = 30_000L)
        assertEquals(300_000L, report.continuousSegmentMs)
        assertEquals(0.0, report.warmingSlopeCPerMinute!!, 1e-9)
        assertEquals(DukoEvidence.QUALIFIED, report.evidence)
        assertTrue("SENSOR_SOURCE_CHANGED" in report.warnings)
    }
}
