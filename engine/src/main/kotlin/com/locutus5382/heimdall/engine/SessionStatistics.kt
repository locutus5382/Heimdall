package com.locutus5382.heimdall.engine

import kotlin.math.ceil

/** Small, deterministic read-only statistics; never invent missing sensor data. */
data class MetricStatistics(
    val count: Int,
    val median: Double,
    val p95: Double,
    val peak: Double,
)

object SessionStatistics {
    /** Median and nearest-rank P95. Missing/nonfinite readings are excluded. */
    fun of(values: Collection<Double>): MetricStatistics? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2]
                     else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        val p95 = sorted[(ceil(n * 0.95).toInt() - 1).coerceIn(0, n - 1)]
        return MetricStatistics(n, median, p95, sorted.last())
    }
}
