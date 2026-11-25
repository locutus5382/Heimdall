package com.locutus5382.heimdall.recording

import com.locutus5382.heimdall.engine.RecorderOverheadMonitor

/**
 * One process-local observer shared by the real recorder and on-demand
 * Diagnostics. Never creates another polling loop or persists samples.
 */
internal object RecorderOverheadHub {
    val monitor = RecorderOverheadMonitor()
}
