package com.locutus5382.heimdall.engine.duko

import kotlin.math.roundToLong

/** Convert a touch-selected fraction of a recorded window into exact monotonic time. */
object DukoRangeSelection {
    const val MIN_WINDOW_MS = 2_000L

    fun select(bounds: DukoWindow, from: Float, to: Float): DukoWindow? {
        if (!from.isFinite() || !to.isFinite() ||
            from !in 0f..1f || to !in 0f..1f || from >= to) return null
        val duration = bounds.durationMs
        val start = bounds.startMs + (duration * from.toDouble()).roundToLong()
        val end = bounds.startMs + (duration * to.toDouble()).roundToLong()
        if (end <= start || end - start < MIN_WINDOW_MS) return null
        if (start < bounds.startMs || end > bounds.endMs) return null
        return DukoWindow(start, end)
    }
}
