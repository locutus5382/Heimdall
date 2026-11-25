package com.locutus5382.heimdall.engine.duko

import kotlin.math.abs

/**
 * M2.1: 2-6 independent measurements of declared comparable workloads.
 *
 * One immutable baseline, up to five candidates. Each pair reuses the
 * conservative A/B evidence gates. Multi-run summaries never pool raw samples,
 * add a "winner", infer FPS, or turn insufficient signals into zero.
 */
data class DukoMultiRunReport(
    val baseline: DukoComparisonInput,
    val candidates: List<DukoComparisonInput>,
    val comparisons: List<DukoComparisonReport>,
    val metricPatterns: Map<DukoMetric, DukoMultiMetricPattern>,
    val qualifiedPairs: Int,
    val warnings: List<String>,
) {
    val sessionCount: Int get() = candidates.size + 1
}

enum class DukoMultiDirection { INCREASE, DECREASE, MIXED, FLAT, INSUFFICIENT }

data class DukoMultiMetricPattern(
    val metric: DukoMetric,
    val baselineMean: Double?,
    val candidateCount: Int,
    val comparableCount: Int,
    val deltas: List<Double?>,
    val medianDelta: Double?,
    val minDelta: Double?,
    val maxDelta: Double?,
    val direction: DukoMultiDirection,
    val warnings: List<String>,
)

object DukoMultiRun {
    const val MIN_RUNS = 2
    const val MAX_RUNS = 6

    fun compare(runs: List<DukoComparisonInput>): DukoMultiRunReport {
        require(runs.size in MIN_RUNS..MAX_RUNS) { "TWO_TO_SIX_RUNS_REQUIRED" }
        require(runs.all { it.sessionId > 0L }) { "INVALID_SESSION_ID" }
        require(runs.map { it.sessionId }.toSet().size == runs.size) {
            "DUPLICATED_SESSION_IDS"
        }
        val baseline = runs.first()
        val candidates = runs.drop(1)
        val pairs = candidates.map { DukoComparison.compare(baseline, it) }
        val allMetrics = (runs.flatMap { it.metrics.keys }).toSortedSet(compareBy { it.name })
        val patterns = allMetrics.associateWith { metric ->
            val differences = pairs.map { it.metrics[metric] }
            // Only a comparison explicitly permitted by the A/B contract
            // contributes numerical deltas to the multi-run pattern.
            val usable = differences.map { item ->
                if (item != null && item.quality != DukoComparisonQuality.NOT_COMPARABLE &&
                    item.delta?.isFinite() == true) item.delta else null
            }
            val qualified = usable.filterNotNull().sorted()
            val median = if (qualified.isEmpty()) null else if (qualified.size % 2 == 1)
                qualified[qualified.size / 2]
            else (qualified[qualified.size / 2 - 1] + qualified[qualified.size / 2]) / 2.0
            // A conservative numerical flat band (not measurement accuracy).
            val baseMean = baseline.metrics[metric]?.timeWeightedMean?.takeIf { it.isFinite() }
            val epsilon = maxOf(0.000001, abs(baseMean ?: 0.0) * 0.005)
            val direction = when {
                qualified.size < 2 -> DukoMultiDirection.INSUFFICIENT
                qualified.all { abs(it) <= epsilon } -> DukoMultiDirection.FLAT
                qualified.all { it > epsilon } -> DukoMultiDirection.INCREASE
                qualified.all { it < -epsilon } -> DukoMultiDirection.DECREASE
                else -> DukoMultiDirection.MIXED
            }
            val warnings = buildList {
                if (qualified.size < candidates.size) add("UNCOMPARABLE_RUNS_EXCLUDED")
                if (qualified.size < 2) add("AT_LEAST_TWO_COMPARABLE_CANDIDATES_NEEDED")
                if (direction == DukoMultiDirection.MIXED) add("VARIATION_BETWEEN_RUNS")
                if (differences.any { it?.quality == DukoComparisonQuality.CAUTION })
                    add("QUALIFIED_PAIR_EVIDENCE")
                add("DESCRIPTIVE_PATTERN_NOT_CAUSAL_PERFORMANCE_CLAIM")
            }
            DukoMultiMetricPattern(
                metric, baseMean, candidates.size, qualified.size,
                usable, median, qualified.firstOrNull(), qualified.lastOrNull(),
                direction, warnings
            )
        }
        val warnings = buildList {
            add("USER_DECLARED_CONDITIONS_NOT_HARDWARE_VERIFICATION")
            add("MATCHING_TAGS_DO_NOT_PROVE_IDENTICAL_GAMEPLAY")
            if (runs.any { it.outcome != DukoSessionOutcome.COMPLETED })
                add("INTERRUPTED_RUNS_NOT_COMPARABLE")
            if (pairs.any { it.quality == DukoComparisonQuality.NOT_COMPARABLE })
                add("ONE_OR_MORE_RUNS_NOT_COMPARABLE")
            if (pairs.any { it.contextWarnings.contains("WINDOW_DURATIONS_DIFFER") })
                add("RECORDING_WINDOWS_DIFFER_IN_LENGTH")
            add("NO_FPS_OR_GAME_PERFORMANCE_WINNER")
        }
        return DukoMultiRunReport(
            baseline = baseline, candidates = candidates,
            comparisons = pairs, metricPatterns = patterns,
            qualifiedPairs = pairs.count { it.quality != DukoComparisonQuality.NOT_COMPARABLE },
            warnings = warnings
        )
    }
}
