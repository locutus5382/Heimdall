package com.locutus5382.heimdall.recording

/**
 * A faulty overlay is presentation failure, never recording failure.
 *
 * The recorder's StateFlow subscriber must continue publishing all later
 * recording, stopping, saving and terminal states even when vendor
 * WindowManager operations or an unexpected HUD view path throw.
 * Release errors are also isolated, rather than killing the subscriber.
 */
internal inline fun safelyDeliverHud(
    render: () -> Unit,
    release: () -> Unit,
    report: (Exception) -> Unit,
): Boolean {
    return try {
        render()
        true
    } catch (failure: Exception) {
        runCatching { release() }
        runCatching { report(failure) }
        false
    }
}
