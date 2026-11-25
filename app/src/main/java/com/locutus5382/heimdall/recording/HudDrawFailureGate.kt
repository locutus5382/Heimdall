package com.locutus5382.heimdall.recording

/**
 * HUD drawing failures are exceptional, but a permanently broken Canvas may
 * redraw repeatedly while Android schedules more invalidations. Dispatch the
 * emergency overlay-disable callback only once per consecutive failure run.
 * Successful drawing resets the gate.
 */
internal class HudDrawFailureGate(private val tripAfter: Int = 3) {
    init { require(tripAfter > 0) }
    private var consecutive = 0
    private var notified = false

    fun success() {
        consecutive = 0
        notified = false
    }

    /** True only for the first threshold crossing in this failure streak. */
    fun failed(): Boolean {
        if (notified) return false
        if (consecutive < tripAfter) consecutive++
        if (consecutive < tripAfter) return false
        notified = true
        return true
    }
}
