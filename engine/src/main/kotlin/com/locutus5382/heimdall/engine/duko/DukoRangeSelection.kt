package com.locutus5382.heimdall.engine.duko

import kotlin.math.roundToLong

/** Convert a touch-selected fraction of a recorded window into exact monotonic time. */
object DukoRangeSelection {
    const val MIN_WINDOW_MS = 2_000L

    /**
     * Focus around one recorded event, including duration and optional visual
     * context. This never claims the contextual rows caused the event.
     */
    fun focus(
        bounds: DukoWindow,
        startMs: Long,
        endMs: Long = startMs,
        contextMs: Long = 30_000L,
    ): DukoWindow? {
        if (contextMs < 0L || startMs < bounds.startMs ||
            endMs < startMs || endMs > bounds.endMs ||
            bounds.durationMs < MIN_WINDOW_MS) return null
        val left = maxOf(bounds.startMs,
            if (startMs < contextMs) 0L else startMs - contextMs)
        val right = minOf(bounds.endMs,
            if (Long.MAX_VALUE - endMs < contextMs) Long.MAX_VALUE
            else endMs + contextMs)
        return if (right - left >= MIN_WINDOW_MS) DukoWindow(left, right)
            else bounds
    }

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
