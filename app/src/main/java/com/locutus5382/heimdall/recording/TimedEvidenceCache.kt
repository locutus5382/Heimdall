package com.locutus5382.heimdall.recording

/**
 * One small time-limited read-only cache for the relatively immutable
 * CPU silicon (cpuinfo_max_freq) limit. Missing evidence is cached for
 * only one window, so a temporarily unreadable sysfs path can recover.
 *
 * Do NOT use this for scaling_max_freq, thermal status or other dynamic
 * evidence: doing so would hide ClusterTune and thermal policy changes.
 */
internal class TimedEvidenceCache<T>(
    private val refreshEveryMs: Long,
    private val readNow: () -> T,
) {
    init { require(refreshEveryMs > 0L) }

    private var lastReadAtMs: Long? = null
    private var stored: T? = null

    @Synchronized fun value(nowMs: Long): T {
        val last = lastReadAtMs
        if (last != null && nowMs >= last &&
            nowMs - last < refreshEveryMs) {
            @Suppress("UNCHECKED_CAST")
            return stored as T
        }
        val latest = readNow()
        stored = latest
        lastReadAtMs = nowMs
        return latest
    }
}
