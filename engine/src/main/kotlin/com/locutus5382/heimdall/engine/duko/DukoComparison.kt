package com.locutus5382.heimdall.engine.duko

import kotlin.math.abs

/**
 * M5a: conservative A/B arithmetic, not an FPS benchmark or a causal claim.
 * Each input is an already-computed, immutable DUKO session/window summary.
 * Never compare unsupported/insufficient sensors as if they were measurements.
 */
data class DukoComparisonInput(
    val sessionId: Long,
    val window: DukoWindow,
    val outcome: DukoSessionOutcome,
    val context: DukoSessionContext,
    val metrics: Map<DukoMetric, DukoMetricAnalysis>,
)

enum class DukoComparisonQuality {
    COMPARABLE, CAUTION, NOT_COMPARABLE
}

data class DukoMetricDifference(
    val metric: DukoMetric,
    val aMean: Double?,
    val bMean: Double?,
    val delta: Double?,
    val percentChange: Double?,
    val quality: DukoComparisonQuality,
    val warnings: List<String>,
)

data class DukoComparisonReport(
    val sessionA: Long,
    val sessionB: Long,
    val quality: DukoComparisonQuality,
    val contextWarnings: List<String>,
    val metrics: Map<DukoMetric, DukoMetricDifference>,
)

/**
 * A/B is explicitly descriptive. A lower CPU/GPU clock or temperature is not
 * proof of higher game FPS, lower device input power, or better performance.
 * User-declared game/driver/fan/performance/ClusterTune tags cannot verify
 * hardware state. No score or winner is inferred from these measurements.
 */
object DukoComparison {
    fun compare(a: DukoComparisonInput, b: DukoComparisonInput): DukoComparisonReport {
        require(a.sessionId > 0 && b.sessionId > 0 && a.sessionId != b.sessionId) {
            "TWO_DISTINCT_SESSIONS_REQUIRED"
        }
        val x = a.context.sanitized()
        val y = b.context.sanitized()
        val warnings = buildList {
            if (a.outcome != DukoSessionOutcome.COMPLETED ||
                b.outcome != DukoSessionOutcome.COMPLETED)
                add("SESSION_NOT_COMPLETED")
            if (x.game.isBlank() || y.game.isBlank())
                add("WORKLOAD_NOT_DECLARED")
            else if (!x.game.equals(y.game, ignoreCase = true))
                add("DIFFERENT_WORKLOAD")
            if (x.emulator != y.emulator) add("EMULATOR_TAG_DIFFERS")
            if (x.graphicsDriver != y.graphicsDriver) add("DRIVER_TAG_DIFFERS")
            if (x.performanceMode != y.performanceMode)
                add("PERFORMANCE_MODE_TAG_DIFFERS")
            if (x.fanProfile != y.fanProfile) add("FAN_TAG_DIFFERS")
            if (x.clusterTuneEnabled != y.clusterTuneEnabled ||
                x.clusterTuneProfile != y.clusterTuneProfile ||
                x.tuningTool != y.tuningTool)
                add("UNDERCLOCK_TAG_DIFFERS")
            if (x.origin != MeasurementOrigin.USER_DECLARED ||
                y.origin != MeasurementOrigin.USER_DECLARED)
                add("TEST_CONDITIONS_NOT_CONFIRMED")
            val long = maxOf(a.window.durationMs, b.window.durationMs)
            val short = minOf(a.window.durationMs, b.window.durationMs)
            if (short.toDouble() / long.toDouble() < 0.8)
                add("WINDOW_DURATIONS_DIFFER")
            add("TAG_MATCH_DOES_NOT_PROVE_EQUAL_WORKLOAD")
        }
        val blocked = "SESSION_NOT_COMPLETED" in warnings ||
            "DIFFERENT_WORKLOAD" in warnings
        val metrics = (a.metrics.keys + b.metrics.keys).associateWith { metric ->
            val left = a.metrics[metric]
            val right = b.metrics[metric]
            val notes = buildList {
                if (left == null || right == null) add("METRIC_MISSING")
                if (left?.window != a.window || right?.window != b.window)
                    add("METRIC_WINDOW_MISMATCH")
                if (left?.evidence == DukoEvidence.INSUFFICIENT ||
                    right?.evidence == DukoEvidence.INSUFFICIENT)
                    add("INSUFFICIENT_METRIC_EVIDENCE")
                if (left?.evidence == DukoEvidence.QUALIFIED ||
                    right?.evidence == DukoEvidence.QUALIFIED)
                    add("QUALIFIED_METRIC_EVIDENCE")
                if (blocked) add("SESSION_CONTEXT_NOT_COMPARABLE")
            }
            val aMean = left?.timeWeightedMean?.takeIf { it.isFinite() }
            val bMean = right?.timeWeightedMean?.takeIf { it.isFinite() }
            val valid = notes.none {
                it in setOf("METRIC_MISSING", "METRIC_WINDOW_MISMATCH",
                    "INSUFFICIENT_METRIC_EVIDENCE", "SESSION_CONTEXT_NOT_COMPARABLE")
            } && aMean != null && bMean != null &&
                left?.evidence != null && right?.evidence != null
            val delta = if (valid) bMean!! - aMean!! else null
            val percent = if (valid && aMean != 0.0) {
                (delta!! / abs(aMean!!)) * 100.0
            } else null
            val quality = when {
                !valid -> DukoComparisonQuality.NOT_COMPARABLE
                notes.isNotEmpty() || warnings.size > 1 ->
                    DukoComparisonQuality.CAUTION
                else -> DukoComparisonQuality.COMPARABLE
            }
            DukoMetricDifference(metric, aMean, bMean, delta, percent,
                quality, notes + if (valid && aMean == 0.0)
                    listOf("ZERO_BASELINE_PERCENT_UNDEFINED") else emptyList())
        }
        val quality = when {
            blocked || metrics.isEmpty() ||
                metrics.values.none { it.quality != DukoComparisonQuality.NOT_COMPARABLE } ->
                DukoComparisonQuality.NOT_COMPARABLE
            warnings.size > 1 || metrics.values.any {
                it.quality != DukoComparisonQuality.COMPARABLE
            } -> DukoComparisonQuality.CAUTION
            else -> DukoComparisonQuality.COMPARABLE
        }
        return DukoComparisonReport(a.sessionId, b.sessionId, quality,
            warnings, metrics)
    }
}
