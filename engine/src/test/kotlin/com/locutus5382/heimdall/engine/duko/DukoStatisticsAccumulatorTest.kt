package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.assertEquals
import org.junit.Test

/** Compare incremental computation to the pre-M4c reference implementation. */
class DukoStatisticsAccumulatorTest {
    private fun equivalent(
        metric: DukoMetric,
        window: DukoWindow,
        rows: List<DukoObservation>,
        cadence: Long = 2_000L,
    ) {
        val incremental = DukoStatisticsAccumulator(metric, window, cadence)
        rows.forEach(incremental::accept)
        val expected = DukoStatistics.analyze(metric, window, rows, cadence)
        assertEquals(expected, incremental.finish())
    }

    @Test fun sourceSwitchesAcrossNullTicksAndReversedClocksMatchReference() {
        val metric = DukoMetric.CPU_LOAD
        val rows = listOf(
            DukoObservation(0L, metric, 20.0, sourceId = "a",
                origin = MeasurementOrigin.VALIDATED_SYSFS),
            DukoObservation(2_000L, metric, null, sourceId = "a"),
            DukoObservation(4_000L, metric, 50.0, sourceId = "b",
                origin = MeasurementOrigin.VALIDATED_SYSFS),
            DukoObservation(3_000L, metric, 35.0, sourceId = "b",
                origin = MeasurementOrigin.VALIDATED_SYSFS),
            DukoObservation(8_000L, metric, Double.NaN, sourceId = "b"),
            DukoObservation(18_000L, metric, 45.0, sourceId = "b",
                origin = MeasurementOrigin.USER_DECLARED),
            DukoObservation(20_000L, metric, 65.0, sourceId = "b",
                origin = MeasurementOrigin.USER_DECLARED),
            DukoObservation(22_000L, metric, 900.0, sourceId = "b"),
            DukoObservation(24_000L, metric, 60.0, sourceId = "b"),
            DukoObservation(30_000L, metric, 30.0, sourceId = "b"),
            DukoObservation(12_000L, DukoMetric.GPU_CLOCK, 400.0),
        )
        equivalent(metric, DukoWindow(0L, 24_000L), rows)
        equivalent(metric, DukoWindow(4_000L, 20_000L), rows)
        equivalent(DukoMetric.GPU_CLOCK, DukoWindow(0L, 24_000L), rows)
    }

    @Test fun longSessionExactOrderStatisticsAndCoverageMatchReference() {
        val metric = DukoMetric.BATTERY_TEMPERATURE
        val rows = ArrayList<DukoObservation>(20_001)
        for (i in 0..20_000) {
            rows += DukoObservation(
                elapsedMs = i * 2_000L,
                metric = metric,
                value = if (i % 117 == 0) null else 29.0 + (i % 35) * 0.1,
                sourceId = "battery",
                origin = MeasurementOrigin.ANDROID_API,
            )
        }
        val window = DukoWindow(0L, 40_000_000L)
        equivalent(metric, window, rows)
        equivalent(metric, DukoWindow(20_000_000L, 20_120_000L), rows)
    }

    @Test fun zeroValidRowsAndIrregularLongGapsMatchReference() {
        val metric = DukoMetric.GPU_LOAD
        val rows = listOf(
            DukoObservation(1_000L, metric, null),
            DukoObservation(2_000L, metric, -5.0),
            DukoObservation(40_000L, metric, 20.0, sourceId = "gpu"),
            DukoObservation(42_000L, metric, 25.0, sourceId = "gpu"),
            DukoObservation(44_000L, metric, 30.0, sourceId = "gpu"),
        )
        equivalent(metric, DukoWindow(0L, 50_000L), rows)
        equivalent(metric, DukoWindow(10_000L, 20_000L), rows)
        equivalent(metric, DukoWindow(40_000L, 44_000L), rows)
        equivalent(DukoMetric.CPU_TEMPERATURE, DukoWindow(0L, 50_000L), rows)
    }
}
