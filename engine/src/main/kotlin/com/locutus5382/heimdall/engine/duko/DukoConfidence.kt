package com.locutus5382.heimdall.engine.duko

/**
 * Numerical confidence is not device calibration. In particular, correct
 * integration of Android battery current and voltage does not establish the
 * accuracy/polarity of an OEM sensor, or measure game/system/wall power.
 *
 * Android callers MUST leave calibration as NOT_VALIDATED until independent,
 * device-specific evidence is available. No app setting, game tag, CPU clock
 * limit, or inferred battery capacity can upgrade this status.
 */
enum class DukoBatteryCalibration {
    NOT_VALIDATED,
    INDEPENDENTLY_VALIDATED,
}

/**
 * A per-domain conclusion layer, deliberately NOT a single opaque score.
 *
 * `metricConclusions` and `thermalTrendConclusions` are limited by session
 * outcome. `energyCalculationEvidence` retains the underlying statistics,
 * while `batteryTerminalConclusion` additionally requires external device
 * calibration and a completed recording to be called SUPPORTED.
 */
data class DukoConfidenceReport(
    val sessionOutcome: DukoSessionOutcome,
    val metricConclusions: Map<DukoMetric, DukoEvidence>,
    val thermalTrendConclusions: Map<DukoMetric, DukoEvidence>,
    val energyCalculationEvidence: DukoEvidence,
    val batteryTerminalConclusion: DukoEvidence,
    val batteryCalibration: DukoBatteryCalibration,
    val warnings: List<String>,
)

object DukoConfidence {
    private fun limitForOutcome(
        evidence: DukoEvidence,
        outcome: DukoSessionOutcome,
    ): DukoEvidence = when (outcome) {
        DukoSessionOutcome.COMPLETED -> evidence
        DukoSessionOutcome.INTERRUPTED ->
            if (evidence == DukoEvidence.SUPPORTED) DukoEvidence.QUALIFIED
            else evidence
        DukoSessionOutcome.NEEDS_RECOVERY -> DukoEvidence.INSUFFICIENT
    }

    fun assess(
        integrity: DukoSessionIntegrity,
        energy: DukoEnergyReport,
        thermal: Map<DukoMetric, DukoThermalReport> = emptyMap(),
        // No device calibration is available from a generic Android API.
        batteryCalibration: DukoBatteryCalibration =
            DukoBatteryCalibration.NOT_VALIDATED,
    ): DukoConfidenceReport {
        val metricConclusions = integrity.metrics.mapValues { (_, result) ->
            limitForOutcome(result.evidence, integrity.outcome)
        }
        val thermalConclusions = thermal.mapValues { (_, result) ->
            limitForOutcome(result.evidence, integrity.outcome)
        }
        val afterSession = limitForOutcome(energy.evidence, integrity.outcome)
        val batteryConclusion = when {
            afterSession == DukoEvidence.INSUFFICIENT ->
                DukoEvidence.INSUFFICIENT
            batteryCalibration == DukoBatteryCalibration.NOT_VALIDATED ->
                DukoEvidence.QUALIFIED
            else -> afterSession
        }
        val warnings = buildList {
            addAll(energy.warnings)
            when (integrity.outcome) {
                DukoSessionOutcome.COMPLETED -> Unit
                DukoSessionOutcome.INTERRUPTED -> add("SESSION_INTERRUPTED")
                DukoSessionOutcome.NEEDS_RECOVERY -> add("SESSION_NEEDS_RECOVERY")
            }
            if (batteryCalibration == DukoBatteryCalibration.NOT_VALIDATED)
                add("BATTERY_ELECTRICAL_CALIBRATION_NOT_VALIDATED")
        }.distinct()
        return DukoConfidenceReport(
            sessionOutcome = integrity.outcome,
            metricConclusions = metricConclusions,
            thermalTrendConclusions = thermalConclusions,
            energyCalculationEvidence = energy.evidence,
            batteryTerminalConclusion = batteryConclusion,
            batteryCalibration = batteryCalibration,
            warnings = warnings,
        )
    }
}
