package com.locutus5382.heimdall.engine.duko

/**
 * M4c stage 2: consume battery-terminal evidence incrementally.
 * Preserves the DukoEnergy evidence contract and exact scalar percentiles.
 * This is not constant-memory: exact percentile values must be retained.
 */
class DukoEnergyAccumulator(
    private val window: DukoWindow,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    private val statistics = DukoStatisticsAccumulator(
        DukoMetric.BATTERY_DISCHARGE_POWER, window, expectedCadenceMs
    )
    private var chargingOrNeutral = 0
    private var invalid = 0
    private var pluggedDischarge = 0
    private var unknownPlug = 0
    private var higherPackVoltage = 0
    private var unknownBatteryStatus = 0
    private var ambiguousBatteryStatus = 0
    private var conflictingBatteryStatus = 0
    private var finished = false

    fun accept(point: DukoBatteryPoint) {
        check(!finished) { "ENERGY_ACCUMULATOR_FINISHED" }
        if (point.elapsedMs < window.startMs ||
            point.elapsedMs > window.endMs) return

        val charging = point.currentMicroAmps != null &&
            point.currentMicroAmps >= 0L
        if (charging) chargingOrNeutral++
        val status = point.batteryStatus
        if (status == null || status == DukoBatteryStatus.UNKNOWN)
            unknownBatteryStatus++
        if (status == DukoBatteryStatus.NOT_CHARGING ||
            status == DukoBatteryStatus.FULL)
            ambiguousBatteryStatus++
        val conflicts = (status == DukoBatteryStatus.CHARGING &&
            point.currentMicroAmps != null && point.currentMicroAmps < 0L) ||
            (status == DukoBatteryStatus.DISCHARGING &&
            point.currentMicroAmps != null && point.currentMicroAmps > 0L)
        if (conflicts) conflictingBatteryStatus++
        if (point.plugged == null) unknownPlug++
        if (point.plugged == true && point.currentMicroAmps != null &&
            point.currentMicroAmps < 0L) pluggedDischarge++

        val validCurrent = point.currentMicroAmps?.takeIf {
            it in -15_000_000L..-1_000L
        }
        val validVoltage = point.voltageMilliVolts?.takeIf {
            it in 2_500..10_000
        }
        if (validVoltage != null && validVoltage > 5_000)
            higherPackVoltage++
        val watts = if (validCurrent != null && validVoltage != null &&
            !conflicts) {
            (-validCurrent.toDouble()) * validVoltage.toDouble() / 1e9
        } else null
        if (watts == null && !charging) invalid++

        statistics.accept(DukoObservation(
            elapsedMs = point.elapsedMs,
            metric = DukoMetric.BATTERY_DISCHARGE_POWER,
            value = watts,
            sourceId = point.sourceId,
            origin = if (point.origin == MeasurementOrigin.ANDROID_API)
                MeasurementOrigin.DERIVED else MeasurementOrigin.UNKNOWN
        ))
    }

    fun finish(): DukoEnergyReport {
        check(!finished) { "ENERGY_ACCUMULATOR_FINISHED" }
        finished = true
        val summary = statistics.finish()
        val energyWh = summary.timeWeightedMean?.let { average ->
            average * summary.coveredMs.toDouble() / 3_600_000.0
        }
        val grade = when {
            summary.evidence == DukoEvidence.INSUFFICIENT ->
                DukoEvidence.INSUFFICIENT
            pluggedDischarge > 0 || unknownPlug > 0 ||
                higherPackVoltage > 0 || unknownBatteryStatus > 0 ||
                ambiguousBatteryStatus > 0 || conflictingBatteryStatus > 0 ||
                summary.evidence == DukoEvidence.QUALIFIED ->
                DukoEvidence.QUALIFIED
            else -> DukoEvidence.SUPPORTED
        }
        val warnings = buildList {
            addAll(summary.warnings)
            if (chargingOrNeutral > 0) add("CHARGING_INTERVAL_EXCLUDED")
            if (invalid > 0) add("ELECTRICAL_READINGS_MISSING_OR_INVALID")
            if (pluggedDischarge > 0)
                add("PLUGGED_BATTERY_DISCHARGE_NOT_TOTAL_INPUT")
            if (unknownPlug > 0) add("PLUG_STATE_UNKNOWN")
            if (unknownBatteryStatus > 0)
                add("BATTERY_STATUS_MISSING_OR_UNKNOWN")
            if (ambiguousBatteryStatus > 0)
                add("BATTERY_STATUS_NOT_UNAMBIGUOUS_DISCHARGE")
            if (conflictingBatteryStatus > 0)
                add("BATTERY_STATUS_CONFLICTS_WITH_CURRENT")
            if (higherPackVoltage > 0)
                add("PACK_VOLTAGE_ABOVE_SINGLE_CELL_REQUIRES_CALIBRATION")
            add("BATTERY_TERMINAL_POWER_NOT_GAME_OR_WALL_POWER")
        }
        return DukoEnergyReport(
            window = window,
            statistics = summary,
            measuredDischargeWh = energyWh,
            measuredDischargeMWh = energyWh?.times(1000.0),
            measuredDischargeMWhPerMinute = energyWh?.let {
                if (summary.coveredMs <= 0L) null
                else it * 1000.0 * 60_000.0 / summary.coveredMs.toDouble()
            },
            chargingOrNeutralSamples = chargingOrNeutral,
            invalidElectricalSamples = invalid,
            pluggedDischargingSamples = pluggedDischarge,
            unknownPlugStateSamples = unknownPlug,
            unknownBatteryStatusSamples = unknownBatteryStatus,
            ambiguousBatteryStatusSamples = ambiguousBatteryStatus,
            conflictingBatteryStatusSamples = conflictingBatteryStatus,
            evidence = grade,
            warnings = warnings
        )
    }
}
