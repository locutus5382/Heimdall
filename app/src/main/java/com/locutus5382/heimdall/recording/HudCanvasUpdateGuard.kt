package com.locutus5382.heimdall.recording

/**
 * Canvas WindowManager update failure boundary. The caller releases both
 * Canvas and UNLOCK ownership and starts its existing retry cooldown.
 * This guard does not own the recorder or any telemetry evidence.
 */
internal inline fun updateCanvasOrRelease(
    update: () -> Unit,
    release: (Exception) -> Unit
): Boolean {
    return try {
        update()
        true
    } catch (failure: Exception) {
        release(failure)
        false
    }
}
