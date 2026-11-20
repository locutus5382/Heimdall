package com.locutus5382.heimdall.engine.duko

import org.junit.Assert.*
import org.junit.Test

class DukoEnergyTest {
    private fun point(
        ms: Long, current: Long? = -2_000_000,
        voltage: Int? = 4_000, plugged: Boolean? = false,
        source: String = "battery-api",
        status: DukoBatteryStatus? = DukoBatteryStatus.DISCHARGING,
    ) = DukoBatteryPoint(ms, current, voltage, plugged, source,
        batteryStatus = status)

    @Test fun constantEightWattDischargeProducesCorrectMeasuredEnergy() {
        val data = (0..5).map { point(it * 2_000L) }
        val a = DukoEnergy.analyze(DukoWindow(0, 10_000), data)
        assertEquals(8.0, a.timeWeightedDischargeWatts!!, 1e-9)
        assertEquals(8.0 * 10.0 / 3_600.0,
            a.measuredDischargeWh!!, 1e-9)
        assertEquals(10_000L, a.statistics.coveredMs)
        assertEquals(1.0, a.statistics.coverage, 1e-9)
        assertEquals(DukoEvidence.SUPPORTED, a.evidence)
        assertTrue("BATTERY_TERMINAL_POWER_NOT_GAME_OR_WALL_POWER" in a.warnings)
    }

    @Test fun chargingAndUnknownCurrentNeverBridgeIntegration() {
        val data = listOf(
            point(0), point(2_000),
            point(4_000, current = 1_000_000, plugged = true),
            point(6_000), point(8_000)
        )
        val a = DukoEnergy.analyze(DukoWindow(0, 8_000), data)
        assertEquals(4_000L, a.statistics.coveredMs)
        assertEquals(0.5, a.statistics.coverage, 1e-9)
        assertEquals(1, a.chargingOrNeutralSamples)
        assertEquals(8 * 4.0 / 3_600.0,
            a.measuredDischargeWh!!, 1e-9)
        assertEquals(DukoEvidence.INSUFFICIENT, a.evidence)
        assertTrue("CHARGING_INTERVAL_EXCLUDED" in a.warnings)
    }

    @Test fun missingVoltageAndLongGapExcludeFalseEnergy() {
        val data = listOf(
            point(0), point(2_000, voltage = null),
            point(12_000), point(14_000)
        )
        val a = DukoEnergy.analyze(DukoWindow(0, 14_000), data)
        assertEquals(2_000L, a.statistics.coveredMs)
        assertEquals(1, a.invalidElectricalSamples)
        assertEquals(8 * 2.0 / 3_600.0, a.measuredDischargeWh!!, 1e-9)
        assertTrue("SAMPLING_GAP" in a.warnings)
    }

    @Test fun pluggedBatteryDischargeIsOnlyQualified() {
        val data = (0..5).map { point(it * 2_000L, plugged = true) }
        val a = DukoEnergy.analyze(DukoWindow(0, 10_000), data)
        assertEquals(DukoEvidence.QUALIFIED, a.evidence)
        assertEquals(6, a.pluggedDischargingSamples)
        assertTrue("PLUGGED_BATTERY_DISCHARGE_NOT_TOTAL_INPUT" in a.warnings)
    }

    @Test fun noElectricalDataNeverFabricatesWatts() {
        val data = listOf(
            point(0, current = null),
            point(2_000, voltage = null),
            point(4_000, current = 100_000, plugged = true)
        )
        val a = DukoEnergy.analyze(DukoWindow(0, 4_000), data)
        assertNull(a.timeWeightedDischargeWatts)
        assertNull(a.measuredDischargeWh)
        assertNull(a.measuredDischargeMWhPerMinute)
        assertEquals(DukoEvidence.INSUFFICIENT, a.evidence)
    }

    @Test fun sourceSwitchCannotCreateFalseIntegratedInterval() {
        val data = listOf(
            point(0, source = "battery-A"),
            point(2_000, source = "battery-B"),
            point(4_000, source = "battery-B")
        )
        val a = DukoEnergy.analyze(DukoWindow(0, 4_000), data)
        assertEquals(2_000L, a.statistics.coveredMs)
        assertTrue("SENSOR_SOURCE_CHANGED" in a.warnings)
    }

    @Test fun widerPackVoltageRequiresCalibrationAndNeverClaimsSupportedEvidence() {
        val data = (0..5).map {
            point(it * 2_000L, current = -1_000_000L, voltage = 8_200)
        }
        val report = DukoEnergy.analyze(DukoWindow(0, 10_000), data)
        assertEquals(8.2, report.timeWeightedDischargeWatts!!, 1e-9)
        assertEquals(DukoEvidence.QUALIFIED, report.evidence)
        assertTrue("PACK_VOLTAGE_ABOVE_SINGLE_CELL_REQUIRES_CALIBRATION"
            in report.warnings)
    }

    @Test fun invalidCurrentAndVoltageRangesAreRejected() {
        val a = DukoEnergy.analyze(DukoWindow(0, 2_000), listOf(
            point(0, current = -99_000_000),
            point(2_000, voltage = 19_000)
        ))
        assertNull(a.measuredDischargeWh)
        assertEquals(DukoEvidence.INSUFFICIENT, a.evidence)
    }
    @Test fun legacyMissingBatteryStatusCannotReceiveSupportedGrade() {
        val report = DukoEnergy.analyze(DukoWindow(0, 10_000),
            (0..5).map { point(it * 2_000L, status = null) })
        assertEquals(8.0, report.timeWeightedDischargeWatts!!, 1e-9)
        assertEquals(DukoEvidence.QUALIFIED, report.evidence)
        assertEquals(6, report.unknownBatteryStatusSamples)
        assertTrue("BATTERY_STATUS_MISSING_OR_UNKNOWN" in report.warnings)
    }

    @Test fun explicitlyUnknownAndroidBatteryStatusAlsoQualifiesEvidence() {
        val report = DukoEnergy.analyze(DukoWindow(0, 10_000),
            (0..5).map {
                point(it * 2_000L, status = DukoBatteryStatus.UNKNOWN)
            })
        assertEquals(DukoEvidence.QUALIFIED, report.evidence)
        assertEquals(6, report.unknownBatteryStatusSamples)
    }

    @Test fun notChargingBypassDischargeIsMeasuredButQualified() {
        val report = DukoEnergy.analyze(DukoWindow(0, 10_000),
            (0..5).map {
                point(it * 2_000L, plugged = true,
                    status = DukoBatteryStatus.NOT_CHARGING)
            })
        assertEquals(8.0, report.timeWeightedDischargeWatts!!, 1e-9)
        assertEquals(DukoEvidence.QUALIFIED, report.evidence)
        assertEquals(6, report.ambiguousBatteryStatusSamples)
        assertTrue("BATTERY_STATUS_NOT_UNAMBIGUOUS_DISCHARGE" in report.warnings)
        assertTrue("PLUGGED_BATTERY_DISCHARGE_NOT_TOTAL_INPUT" in report.warnings)
    }

    @Test fun chargingStatusWithNegativeCurrentCannotProduceEnergy() {
        val report = DukoEnergy.analyze(DukoWindow(0, 10_000),
            (0..5).map {
                point(it * 2_000L, current = -2_000_000L,
                    status = DukoBatteryStatus.CHARGING)
            })
        assertNull(report.measuredDischargeWh)
        assertNull(report.timeWeightedDischargeWatts)
        assertEquals(6, report.conflictingBatteryStatusSamples)
        assertEquals(DukoEvidence.INSUFFICIENT, report.evidence)
        assertTrue("BATTERY_STATUS_CONFLICTS_WITH_CURRENT" in report.warnings)
    }

    @Test fun isolatedContradictionBreaksIntegrationAndDowngradesGrade() {
        val report = DukoEnergy.analyze(DukoWindow(0, 10_000),
            (0..5).map {
                point(it * 2_000L, status = if (it == 2)
                    DukoBatteryStatus.CHARGING
                    else DukoBatteryStatus.DISCHARGING)
            })
        assertEquals(6_000L, report.statistics.coveredMs)
        assertEquals(DukoEvidence.INSUFFICIENT, report.evidence)
        assertEquals(1, report.conflictingBatteryStatusSamples)
        assertTrue("BATTERY_STATUS_CONFLICTS_WITH_CURRENT" in report.warnings)
    }

}
