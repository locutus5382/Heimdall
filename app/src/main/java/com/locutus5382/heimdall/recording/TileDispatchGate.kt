package com.locutus5382.heimdall.recording

/**
 * Main-thread-only guard for a Quick Settings command. Acquire before the
 * first suspend point, release in finally after dispatch/refresh completes.
 * This prevents two fast taps from both reading Idle and sending START.
 * The recorder Service remains the ultimate authority for lifecycle safety.
 */
internal class TileDispatchGate {
    private var inFlight = false

    fun acquire(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun release() {
        check(inFlight) { "UNBALANCED_TILE_DISPATCH" }
        inFlight = false
    }
}
