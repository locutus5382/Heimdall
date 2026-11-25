package com.locutus5382.heimdall.recording

/**
 * Best-effort rollback even when addView attached a window but onAttach
 * callbacks have not yet changed View.isAttachedToWindow.
 *
 * Calling removeViewImmediate on an unattached View may throw; that failure
 * is harmless during cleanup and must not change recorder status.
 */
internal inline fun rollbackHudWindow(
    remove: () -> Unit,
    report: (Exception) -> Unit,
): Boolean = try {
    remove()
    true
} catch (failure: Exception) {
    runCatching { report(failure) }
    false
}
