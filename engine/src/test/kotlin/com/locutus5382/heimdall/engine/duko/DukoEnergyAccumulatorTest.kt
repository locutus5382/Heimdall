package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

/**
 * Compare streaming reports against the original, list-based battery-energy
 * implementation. Exact data-class equality includes percentiles, warnings,
 * coverage, status counters, source transitions and integrated watt-hours.
 */
class DukoEnergyAccumulatorTest {
    private fun compare(
        window: DukoWindow,
        points: List<DukoBatteryPoint>,
        cadence: Long = 2_000L,
    ) {
        val accumulator = DukoEnergyAccumulator(window, cadence)
        points.forEach(accumulator::accept)
        val expected = DukoEnergy.analyze(window, points, cadence)
        assertEquals(expected, accumulator.finish())
        try {
            accumulator.finish()
            fail("finalization must be single-use")
        } catch (_: IllegalStateException) { }
        try {
            accumulator.accept(DukoBatteryPoint(0L, null, null, null))
            fail("cannot append after finalization")
        } catch (_: IllegalStateException) { }
    }

    @Test fun twelveThousandRowsAndSmallSelectionMatchReferenceExactly() {
        val points = ArrayList<DukoBatteryPoint>(12_020)
        for (i in 0..12_000) {
            val status = when {
                i % 73 == 0 -> null
                i % 47 == 0 -> DukoBatteryStatus.UNKNOWN
                i % 41 == 0 -> DukoBatteryStatus.CHARGING
                i % 37 == 0 -> DukoBatteryStatus.NOT_CHARGING
                i % 31 == 0 -> DukoBatteryStatus.FULL
                else -> DukoBatteryStatus.DISCHARGING
            }
            val current = when {
                i % 97 == 0 -> null
                i % 23 == 0 -> 1_000_000L
                i % 19 == 0 -> -17_000_000L
                else -> -1_000_000L - (i % 9) * 90_000L
            }
            val voltage = when {
                i % 83 == 0 -> null
                i % 71 == 0 -> 12_000
                i % 53 == 0 -> 8_200
                else -> 3_700 + (i % 50)
            }
            points += DukoBatteryPoint(
                elapsedMs = i * 2_000L,
                currentMicroAmps = current,
                voltageMilliVolts = voltage,
                plugged = when {
                    i % 29 == 0 -> null
                    i % 17 == 0 -> true
                    else -> false
                },
                sourceId = if (i < 6_000) "battery-A" else "battery-B",
                origin = if (i % 61 == 0)
                    MeasurementOrigin.UNKNOWN else MeasurementOrigin.ANDROID_API,
                batteryStatus = status,
            )
        }
        // The appended earlier timestamp is deliberately out of order.
        points.add(DukoBatteryPoint(12_000_000L, -2_000_000L, 3_900, false,
            batteryStatus = DukoBatteryStatus.DISCHARGING))
        // And a reading just beyond the selected window must be ignored.
        points.add(DukoBatteryPoint(24_002_000L, -2_000_000L, 3_900, false,
            batteryStatus = DukoBatteryStatus.DISCHARGING))
        compare(DukoWindow(0L, 24_000_000L), points)
        compare(DukoWindow(4_000_000L, 4_060_000L), points)
    }

    @Test fun sparseConflictingAndOutOfRangeEvidenceMatchReference() {
        val points = listOf(
            DukoBatteryPoint(0L, -2_000_000L, 4_000, false,
                batteryStatus = DukoBatteryStatus.DISCHARGING),
            DukoBatteryPoint(2_000L, -2_000_000L, 4_000, true,
                batteryStatus = DukoBatteryStatus.NOT_CHARGING),
            DukoBatteryPoint(4_000L, -2_000_000L, 4_000, false,
                batteryStatus = DukoBatteryStatus.CHARGING),
            DukoBatteryPoint(3_000L, -2_000_000L, 4_000, false,
                batteryStatus = DukoBatteryStatus.DISCHARGING),
            DukoBatteryPoint(20_000L, -2_000_000L, 8_200, null,
                batteryStatus = DukoBatteryStatus.DISCHARGING),
            DukoBatteryPoint(22_000L, 0L, 4_000, false,
                batteryStatus = DukoBatteryStatus.DISCHARGING),
            DukoBatteryPoint(24_000L, null, null, null),
        )
        compare(DukoWindow(0L, 24_000L), points)
        compare(DukoWindow(5_000L, 18_000L), points)
        compare(DukoWindow(2_000L, 4_000L), points)
    }

    @Test fun perfectDischargeHasExactReferenceEnergyAndNoWallPowerClaim() {
        val window = DukoWindow(0L, 10_000L)
        val points = (0..5).map {
            DukoBatteryPoint(it * 2_000L, -2_000_000L, 4_000, false,
                batteryStatus = DukoBatteryStatus.DISCHARGING)
        }
        val acc = DukoEnergyAccumulator(window)
        points.forEach(acc::accept)
        val actual = acc.finish()
        assertEquals(8.0, actual.timeWeightedDischargeWatts!!, 1e-9)
        assertEquals(8.0 * 10.0 / 3_600.0, actual.measuredDischargeWh!!, 1e-9)
        assertEquals(DukoEvidence.SUPPORTED, actual.evidence)
        assertTrue("BATTERY_TERMINAL_POWER_NOT_GAME_OR_WALL_POWER" in actual.warnings)
        assertEquals(DukoEnergy.analyze(window, points), actual)
    }
}
