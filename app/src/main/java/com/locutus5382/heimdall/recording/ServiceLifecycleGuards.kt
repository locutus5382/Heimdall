package com.locutus5382.heimdall.recording

/**
 * Main-thread-only Service command accounting. A boolean is insufficient:
 * command A completing must not clear the in-flight state of command B.
 *
 * Keep the latest Android startId to use with stopSelfResult(), never the
 * unconditional stopSelf() that can terminate a newer queued START.
 */
internal class ServiceCommandTracker {
    private var pendingCommands: Int = 0
    private var latestStartId: Int? = null

    fun begin(startId: Int) {
        require(startId > 0) { "INVALID_SERVICE_START_ID" }
        latestStartId = startId
        pendingCommands++
    }

    fun finish() {
        check(pendingCommands > 0) { "UNBALANCED_SERVICE_COMMAND" }
        pendingCommands--
    }

    fun idleStartId(): Int? =
        if (pendingCommands == 0) latestStartId else null
}

/** Deduplicates same-phase notification refreshes during 2-second sampling. */
internal class RecorderNotificationGate {
    private var lastKey: Pair<String, String?>? = null

    fun shouldPost(message: String, action: String?): Boolean {
        val next = message to action
        if (lastKey == next) return false
        lastKey = next
        return true
    }

    fun reset() { lastKey = null }
}
