package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

/** Exact full-report parity with the existing list-based thermal analyzer. */
class DukoThermalAccumulatorTest {
    private fun equivalent(
        metric: DukoMetric,
        window: DukoWindow,
        rows: List<DukoObservation>,
    ) {
        val stream = DukoThermalAccumulator(metric, window)
        rows.forEach(stream::accept)
        val expected = DukoThermal.analyze(metric, window, rows)
        assertEquals(expected, stream.finish())
        try {
            stream.finish()
            fail("finish must be single-use")
        } catch (_: IllegalStateException) { }
    }

    @Test fun longSegmentAndFragmentedSeriesMatchOriginal() {
        val metrics = listOf(
            DukoMetric.CPU_TEMPERATURE,
            DukoMetric.GPU_TEMPERATURE,
            DukoMetric.BATTERY_TEMPERATURE
        )
        for (metric in metrics) {
            val rows = ArrayList<DukoObservation>(12_100)
            for (i in 0..12_000) {
                val value = when {
                    i % 331 == 0 -> null
                    i % 181 == 0 -> Double.NaN
                    else -> 29.0 + i * 0.0004 + (i % 17) * 0.03
                }
                rows.add(DukoObservation(
                    i * 2_000L, metric, value,
                    sourceId = if (i < 6_000) "sensor-A" else "sensor-B",
                    origin = MeasurementOrigin.VALIDATED_SYSFS
                ))
            }
            rows.add(DukoObservation(
                12_000_000L, metric, 35.0,
                sourceId = "sensor-B",
                origin = MeasurementOrigin.VALIDATED_SYSFS
            ))
            equivalent(metric, DukoWindow(0L, 24_000_000L), rows)
            equivalent(metric, DukoWindow(4_000_000L, 4_180_000L), rows)
        }
    }

    @Test fun exactLongRegressionAndTieBreakingMatchReference() {
        val metric = DukoMetric.BATTERY_TEMPERATURE
        val rows = (0..150).map { i ->
            DukoObservation(i * 2_000L, metric, 28.0 + i * 0.02,
                sourceId = "battery",
                origin = MeasurementOrigin.ANDROID_API)
        }
        equivalent(metric, DukoWindow(0L, 300_000L), rows)
        // Equal-duration segments: the reference keeps the first.
        val tied = rows.take(70) + listOf(
            DukoObservation(140_000L, metric, null, sourceId = "battery")
        ) + (71..140).map { i ->
            DukoObservation(i * 2_000L, metric, 45.0 + i * 0.01,
                sourceId = "battery",
                origin = MeasurementOrigin.ANDROID_API)
        }
        equivalent(metric, DukoWindow(0L, 280_000L), tied)
    }

    @Test fun invalidAndEmptyWindowReportsAreIdentical() {
        val metric = DukoMetric.GPU_TEMPERATURE
        val rows = listOf(
            DukoObservation(0L, metric, null),
            DukoObservation(2_000L, metric, Double.NaN),
            DukoObservation(4_000L, metric, 55.0),
            DukoObservation(6_000L, metric, 56.0),
            DukoObservation(8_000L, DukoMetric.CPU_TEMPERATURE, 40.0)
        )
        equivalent(metric, DukoWindow(0L, 10_000L), rows)
        equivalent(metric, DukoWindow(20_000L, 40_000L), rows)
    }
}
