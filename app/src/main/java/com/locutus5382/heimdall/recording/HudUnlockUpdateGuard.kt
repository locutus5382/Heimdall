package com.locutus5382.heimdall.recording

/**
 * Failure boundary for the separate UNLOCK window. If its WindowManager update
 * fails, the main HUD must not remain click-through without a usable unlock
 * control. Cleanup failure must not prevent releasing the lock.
 *
 * Callers own the windows; this guard only orders the failure recovery.
 */
internal inline fun updateUnlockOrReleaseLock(
    update: () -> Unit,
    releaseWindow: () -> Unit,
    releaseLock: () -> Unit
): Boolean {
    return try {
        update()
        true
    } catch (_: Exception) {
        try {
            releaseWindow()
        } finally {
            releaseLock()
        }
        false
    }
}
