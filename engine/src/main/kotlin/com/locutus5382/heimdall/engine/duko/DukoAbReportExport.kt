package com.locutus5382.heimdall.engine.duko

import java.util.Locale

/**
 * M6a: deterministic, offline, comparison-only DUKO Report Option B.
 * No single-session report and no inferred FPS, winner or causal improvement.
 * Formatting is independent of Android so evidence gates are JVM-testable.
 */
object DukoAbReportExport {
    private const val VERSION = 1

    fun text(
        report: DukoComparisonReport,
        windowA: DukoWindow,
        windowB: DukoWindow,
    ): String = buildString {
        appendLine("DUKO A/B REPORT | format v$VERSION")
        appendLine("Session A: ${report.sessionA} | monotonic window: ${windowA.startMs}..${windowA.endMs} ms")
        appendLine("Session B: ${report.sessionB} | monotonic window: ${windowB.startMs}..${windowB.endMs} ms")
        appendLine("Overall evidence: ${report.quality.name}")
        appendLine("Context warnings: ${report.contextWarnings.joinToString(", ").ifEmpty { "none" }}")
        appendLine()
        appendLine("Metric | A time-weighted mean | B time-weighted mean | B-A | Change (%) | Evidence | Warnings")
        report.metrics.toSortedMap(compareBy { it.name }).forEach { (metric, item) ->
            // A/B means are retained as descriptive evidence, but a blocked
            // delta must NEVER appear as a quantitative comparison.
            val comparable = item.quality != DukoComparisonQuality.NOT_COMPARABLE
            appendLine(listOf(
                metric.name + " (" + metric.unit.symbol + ")",
                number(item.aMean), number(item.bMean),
                if (comparable) number(item.delta) else "N/A",
                if (comparable) number(item.percentChange) else "N/A",
                item.quality.name,
                item.warnings.joinToString(", ").ifEmpty { "none" },
            ).joinToString(" | "))
        }
        appendLine()
        appendLine("Evidence notes: user-declared settings are not verified hardware states.")
        appendLine("Battery-terminal power is not game, wall or total device input power.")
        appendLine("Telemetry differences do not establish FPS, causality, performance improvement or a winner.")
        appendLine("No physical Odin 2 Portal sensor calibration or overlay validation is implied.")
    }

    fun csv(
        report: DukoComparisonReport,
        windowA: DukoWindow,
        windowB: DukoWindow,
    ): String = buildString {
        appendLine("report_version,session_a,session_b,window_a_start_ms,window_a_end_ms,window_b_start_ms,window_b_end_ms,overall_quality,context_warnings,metric,unit,a_time_weighted_mean,b_time_weighted_mean,delta_b_minus_a,percent_change,metric_quality,metric_warnings")
        report.metrics.toSortedMap(compareBy { it.name }).forEach { (metric, item) ->
            val comparable = item.quality != DukoComparisonQuality.NOT_COMPARABLE
            appendLine(listOf(
                VERSION.toString(), report.sessionA.toString(),
                report.sessionB.toString(), windowA.startMs.toString(),
                windowA.endMs.toString(), windowB.startMs.toString(),
                windowB.endMs.toString(), report.quality.name,
                report.contextWarnings.joinToString(";"),
                metric.name, metric.unit.symbol,
                number(item.aMean), number(item.bMean),
                if (comparable) number(item.delta) else "",
                if (comparable) number(item.percentChange) else "",
                item.quality.name, item.warnings.joinToString(";"),
            ).joinToString(",") { escapeCsv(it) })
        }
    }

    private fun number(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "%.6f", it)
        } ?: "N/A"

    private fun escapeCsv(raw: String): String {
        // Neutralize spreadsheet formula execution for all text fields.
        val value = if (raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@'))
            "'" + raw else raw
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
