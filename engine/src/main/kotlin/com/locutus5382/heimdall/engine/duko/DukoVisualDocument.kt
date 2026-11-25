package com.locutus5382.heimdall.engine.duko

import java.util.Locale

/** PDF and PNG use the same frozen, Android-free, comparison-only evidence. */
data class DukoVisualRow(
    val title: String,
    val value: String,
    val explanation: String = "",
    val caution: Boolean = false,
)
data class DukoVisualSection(val title: String, val rows: List<DukoVisualRow>)
data class DukoVisualDocument(
    val title: String,
    val subtitle: String,
    val sections: List<DukoVisualSection>,
    val caveats: List<String>,
    val version: Int = 1,
)

object DukoVisualReport {
    private val metrics = listOf(
        DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD,
        DukoMetric.CPU_CLOCK, DukoMetric.GPU_CLOCK,
        DukoMetric.BATTERY_TEMPERATURE,
        DukoMetric.CPU_TEMPERATURE, DukoMetric.GPU_TEMPERATURE,
        DukoMetric.AVAILABLE_RAM, DukoMetric.BATTERY_LEVEL,
        DukoMetric.BATTERY_DISCHARGE_POWER,
        DukoMetric.BATTERY_CURRENT, DukoMetric.BATTERY_VOLTAGE,
    )
    private fun number(v: Double?): String =
        v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"
    private fun label(metric: DukoMetric) =
        metric.name.replace('_', ' ') + " (" + metric.unit.symbol + ")"
    private fun window(v: DukoWindow) =
        v.startMs.toString() + ".." + v.endMs + " ms (" +
            (v.durationMs / 1000L) + "s)"
    private val standardCaveats = listOf(
        "DUKO Report: device telemetry comparisons only.",
        "Reported game/emulator/driver/fan/tuner settings are user-declared.",
        "Matching tags do not establish identical gameplay workloads.",
        "Observed clocks, temperatures and load do not measure FPS or frame pacing.",
        "Differences do not prove causality, improvement or a winning setting.",
        "Unmeasured values remain N/A; battery-terminal power is not game/wall power."
    )

    fun ab(
        report: DukoComparisonReport,
        windowA: DukoWindow,
        windowB: DukoWindow,
    ): DukoVisualDocument {
        val sections = listOf(
            DukoVisualSection("MEASURED SESSIONS", listOf(
                DukoVisualRow("A / BASELINE #" + report.sessionA, window(windowA)),
                DukoVisualRow("B / CANDIDATE #" + report.sessionB, window(windowB)),
                DukoVisualRow("Overall evidence", report.quality.name.replace('_', ' '),
                    report.contextWarnings.joinToString("; "),
                    report.quality == DukoComparisonQuality.NOT_COMPARABLE),
            )),
            DukoVisualSection("A/B METRIC EVIDENCE", metrics.mapNotNull { metric ->
                report.metrics[metric]?.let { item ->
                    val comparable = item.quality != DukoComparisonQuality.NOT_COMPARABLE
                    val delta = if (comparable) {
                        "  |  B-A " + number(item.delta) + " " + metric.unit.symbol
                    } else "  |  DELTA BLOCKED"
                    DukoVisualRow(label(metric),
                        "A " + number(item.aMean) + "  |  B " +
                            number(item.bMean) + delta,
                        item.quality.name + " | " +
                            item.warnings.joinToString("; ").ifEmpty { "No metric warnings" },
                        !comparable)
                }
            })
        )
        return DukoVisualDocument(
            "DUKO / A-B COMPARISON",
            "Sessions #" + report.sessionA + " and #" + report.sessionB +
                " | " + report.quality.name,
            sections, standardCaveats
        )
    }

    fun multi(report: DukoMultiRunReport): DukoVisualDocument {
        val base = report.baseline
        val scope = DukoVisualSection("COMPARISON SCOPE", listOf(
            DukoVisualRow("Baseline #" + base.sessionId, window(base.window)),
            DukoVisualRow("Candidates", report.candidates.joinToString(", ") {
                "#" + it.sessionId }, report.qualifiedPairs.toString() + " / " +
                report.candidates.size + " eligible pairs"),
            DukoVisualRow("Evidence constraints",
                "Baseline-versus-candidate only",
                report.warnings.joinToString("; "), report.qualifiedPairs == 0),
        ))
        val patterns = DukoVisualSection("ACROSS-RUN OBSERVATIONS",
            metrics.mapNotNull { metric ->
                report.metricPatterns[metric]?.let { p ->
                    val enough = p.comparableCount >= 2
                    DukoVisualRow(label(metric),
                        if (enough) "Median B-A " + number(p.medianDelta) +
                            " " + metric.unit.symbol + "  |  range " +
                            number(p.minDelta) + ".." + number(p.maxDelta)
                        else "INSUFFICIENT COMPARABLE CANDIDATES",
                        p.comparableCount.toString() + " / " + p.candidateCount +
                            " candidate deltas | " + p.direction.name +
                            " | No causal inference",
                        !enough)
                }
            })
        val pairSections = report.comparisons.mapIndexed { index, comparison ->
            val candidate = report.candidates[index]
            DukoVisualSection("CANDIDATE #" + candidate.sessionId +
                " VS BASELINE #" + base.sessionId,
                listOf(
                    DukoVisualRow("Observed candidate window", window(candidate.window)),
                    DukoVisualRow("Pair evidence", comparison.quality.name,
                        comparison.contextWarnings.joinToString("; "),
                        comparison.quality == DukoComparisonQuality.NOT_COMPARABLE)
                ) + metrics.mapNotNull { metric ->
                    comparison.metrics[metric]?.let { item ->
                        val comparable = item.quality != DukoComparisonQuality.NOT_COMPARABLE
                        DukoVisualRow(label(metric),
                            "A " + number(item.aMean) + " | B " + number(item.bMean) +
                                if (comparable) " | B-A " + number(item.delta)
                                else " | DELTA BLOCKED",
                            item.quality.name + " | " +
                                item.warnings.joinToString("; ").ifEmpty {
                                    "No metric warnings" },
                            !comparable)
                    }
                })
        }
        return DukoVisualDocument(
            "DUKO / MULTI-RUN COMPARISON",
            "Baseline #" + base.sessionId + " | " +
                report.sessionCount + " recorded sessions",
            listOf(scope, patterns) + pairSections,
            standardCaveats +
                "Multi-run directions describe observed sensor changes only; no ranking."
        )
    }
}
