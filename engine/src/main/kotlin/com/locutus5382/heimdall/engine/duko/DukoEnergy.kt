package com.locutus5382.heimdall.engine.duko

/**
 * D3: battery-terminal discharge telemetry, not total system/wall power.
 *
 * Android BATTERY_PROPERTY_CURRENT_NOW is in microamperes (negative for
 * discharge). ACTION_BATTERY_CHANGED voltage is in millivolts. This module
 * accepts independently captured pairs and never infers electrical energy
 * from percentage changes or unverified nominal battery capacity.
 */
/** Android battery status, mapped at the Android boundary (no Android engine dependency). */
enum class DukoBatteryStatus {
    UNKNOWN, CHARGING, DISCHARGING, NOT_CHARGING, FULL
}

data class DukoBatteryPoint(
    val elapsedMs: Long,
    val currentMicroAmps: Long?,
    val voltageMilliVolts: Int?,
    val plugged: Boolean?,
    val sourceId: String = "Android:BATTERY_PROPERTY_CURRENT_NOW+EXTRA_VOLTAGE",
    val origin: MeasurementOrigin = MeasurementOrigin.ANDROID_API,
    // Null for historical v1-v4 rows: unknown is not evidence of discharging.
    val batteryStatus: DukoBatteryStatus? = null,
)

data class DukoEnergyReport(
    val window: DukoWindow,
    val statistics: DukoMetricAnalysis,
    val measuredDischargeWh: Double?,
    val measuredDischargeMWh: Double?,
    val measuredDischargeMWhPerMinute: Double?,
    val chargingOrNeutralSamples: Int,
    val invalidElectricalSamples: Int,
    val pluggedDischargingSamples: Int,
    val unknownPlugStateSamples: Int,
    val unknownBatteryStatusSamples: Int,
    val ambiguousBatteryStatusSamples: Int,
    val conflictingBatteryStatusSamples: Int,
    val evidence: DukoEvidence,
    val warnings: List<String>,
) {
    val timeWeightedDischargeWatts: Double?
        get() = statistics.timeWeightedMean
}

/**
 * Do not bridge charging periods, missing readings, source changes, bad
 * timestamps or long sampling gaps. The coverage denominator is the FULL
 * selected window, not merely the duration of valid discharge.
 */
object DukoEnergy {
    fun analyze(
        window: DukoWindow,
        readings: List<DukoBatteryPoint>,
        expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
    ): DukoEnergyReport {
        var chargingOrNeutral = 0
        var invalid = 0
        var pluggedDischarge = 0
        var unknownPlug = 0
        var higherPackVoltage = 0
        var unknownBatteryStatus = 0
        var ambiguousBatteryStatus = 0
        var conflictingBatteryStatus = 0
        val observations = readings.filter {
            it.elapsedMs in window.startMs..window.endMs
        }.map { point ->
            // Do not interpret positive/zero battery-terminal current as
            // "power used by the device": it reflects charging/net balance.
            val charging = point.currentMicroAmps != null &&
                point.currentMicroAmps >= 0L
            if (charging) chargingOrNeutral++
            val status = point.batteryStatus
            if (status == null || status == DukoBatteryStatus.UNKNOWN)
                unknownBatteryStatus++
            if (status == DukoBatteryStatus.NOT_CHARGING ||
                status == DukoBatteryStatus.FULL)
                ambiguousBatteryStatus++
            // A charging declaration with negative current is contradictory:
            // preserve the original point in SQLite, but never integrate it.
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
            if (validVoltage != null && validVoltage > 5_000) higherPackVoltage++
            val watts = if (validCurrent != null && validVoltage != null &&
                !conflicts) {
                (-validCurrent.toDouble()) * validVoltage.toDouble() / 1e9
            } else null
            if (watts == null && !charging) invalid++
            DukoObservation(
                elapsedMs = point.elapsedMs,
                metric = DukoMetric.BATTERY_DISCHARGE_POWER,
                value = watts,
                sourceId = point.sourceId,
                origin = if (point.origin == MeasurementOrigin.ANDROID_API)
                    MeasurementOrigin.DERIVED else MeasurementOrigin.UNKNOWN
            )
        }
        val summary = DukoStatistics.analyze(
            DukoMetric.BATTERY_DISCHARGE_POWER,
            window,
            observations,
            expectedCadenceMs
        )
        val energyWh = summary.timeWeightedMean?.let { average ->
            average * summary.coveredMs.toDouble() / 3_600_000.0
        }
        // A plugged-in reading may still have negative battery current:
        // the measured battery contribution is real but is NOT device input.
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
