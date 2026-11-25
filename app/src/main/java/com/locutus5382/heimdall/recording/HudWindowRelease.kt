package com.locutus5382.heimdall.recording

/**
 * Prevent replacement of a WindowManager overlay whose removal may have
 * failed while it is STILL attached. Clearing the only reference before
 * observing detachment could create a duplicate Canvas or unlock chip.
 *
 * Returns true only after the window is observed detached, including cases
 * where removeViewImmediate throws because it was already removed.
 * If it remains attached, hide it and retain the original owner for retry.
 * The caller must not create a second window while the owner remains.
 */
internal inline fun releaseOverlayOrRetain(
    remove: () -> Unit,
    attached: () -> Boolean,
    conceal: () -> Unit,
    report: (Exception) -> Unit,
): Boolean {
    try {
        remove()
    } catch (failure: Exception) {
        runCatching { report(failure) }
    }
    // Querying attached state may itself be unreliable on a damaged vendor
    // WindowManager. Unknown is treated as still owned to avoid duplication.
    val stillAttached = runCatching { attached() }.getOrDefault(true)
    if (stillAttached) runCatching { conceal() }
    return !stillAttached
}
