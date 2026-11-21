package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.TileDispatchGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TileDispatchGateTest {
    @Test fun repeatedTapsCannotOverlapPendingDiskReadOrServiceDispatch() {
        val gate = TileDispatchGate()
        assertTrue(gate.acquire())
        repeat(100) { assertFalse(gate.acquire()) }
        gate.release()
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        gate.release()
    }

    @Test fun failedDispatchReleasesGateForRetry() {
        val gate = TileDispatchGate()
        try {
            assertTrue(gate.acquire())
            throw IllegalStateException("simulated FGS denial")
        } catch (_: IllegalStateException) {
        } finally {
            gate.release()
        }
        assertTrue(gate.acquire())
        gate.release()
    }

    @Test fun unbalancedReleaseIsDetected() {
        val gate = TileDispatchGate()
        try {
            gate.release()
            fail("unbalanced release must fail")
        } catch (_: IllegalStateException) {
        }
    }
}
