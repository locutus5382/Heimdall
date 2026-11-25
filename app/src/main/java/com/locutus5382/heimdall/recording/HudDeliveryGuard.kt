package com.locutus5382.heimdall.recording

import kotlinx.coroutines.CancellationException

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
    } catch (cancel: CancellationException) {
        // The Service scope is stopping; preserve coroutine cancellation.
        throw cancel
    } catch (failure: Exception) {
        runCatching { release() }
        runCatching { report(failure) }
        false
    }
}
