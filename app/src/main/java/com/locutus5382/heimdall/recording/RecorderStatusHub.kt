package com.locutus5382.heimdall.recording

import com.locutus5382.heimdall.engine.RecorderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only UI projection in the SAME app process. Commands go exclusively
 * through HeimdallRecorderService; UI never touches an engine or DB writer.
 */
object RecorderStatusHub {
    private val mutable = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
    val state: StateFlow<RecorderStatus> = mutable
    internal fun publish(value: RecorderStatus) { mutable.value = value }
}
