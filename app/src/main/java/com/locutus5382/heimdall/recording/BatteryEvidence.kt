package com.locutus5382.heimdall.recording

import android.os.BatteryManager

/** Preserve raw electrical evidence separately from interpretive values. */
internal object BatteryEvidence {
    fun acceptedVoltageMv(raw: Int?): Int? =
        raw?.takeIf { it in 2_500..10_000 }

    fun interpretedCurrentUa(raw: Long?, status: Int?): Long? {
        val n = raw ?: return null
        if (n == Long.MIN_VALUE || n !in -15_000_000L..15_000_000L ||
            kotlin.math.abs(n) < 1_000L) return null
        return when (status) {
            BatteryManager.BATTERY_STATUS_DISCHARGING -> n.takeIf { it < 0L }
            BatteryManager.BATTERY_STATUS_CHARGING -> n.takeIf { it > 0L }
            else -> null
        }
    }
}

/** Query at most once per ten seconds, without fabricating intervening data. */
internal class ThermalHeadroomGate(private val intervalMs: Long = 10_000L) {
    init { require(intervalMs >= 10_000L) }
    private var previous: Long? = null
    fun shouldQuery(nowMs: Long): Boolean {
        val last = previous
        if (last != null && nowMs >= last && nowMs - last < intervalMs)
            return false
        previous = nowMs
        return true
    }
}
