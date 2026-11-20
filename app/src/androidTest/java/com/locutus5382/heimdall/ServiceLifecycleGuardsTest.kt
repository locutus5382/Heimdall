package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.ServiceCommandTracker
import com.locutus5382.heimdall.recording.RecorderNotificationGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the concurrency rules independently of the OEM service scheduler. */
@RunWith(AndroidJUnit4::class)
class ServiceLifecycleGuardsTest {
    @Test fun overlappingCommandsCannotClearTheOthersPendingState() {
        val guard = ServiceCommandTracker()
        assertNull(guard.idleStartId())
        guard.begin(21)
        guard.begin(22)
        assertNull(guard.idleStartId())
        guard.finish() // Older command finishes while a newer START is queued.
        assertNull("First completion must not mark the Service idle", guard.idleStartId())
        guard.finish()
        assertEquals(22, guard.idleStartId())
    }

    @Test fun inspectIsAlsoTrackedAndCannotStopActiveRecordingCommand() {
        val guard = ServiceCommandTracker()
        guard.begin(41) // START in progress.
        guard.begin(42) // INSPECT arrives while START is still suspended.
        guard.finish() // INSPECT returns first.
        assertNull(guard.idleStartId())
        guard.finish() // START returns after INSPECT.
        assertEquals(42, guard.idleStartId())
    }

    @Test fun repeatedRecordingSamplesDoNotReissueNotification() {
        val gate = RecorderNotificationGate()
        assertTrue(gate.shouldPost("Recording session #7", "STOP"))
        repeat(25) {
            assertFalse(gate.shouldPost("Recording session #7", "STOP"))
        }
        assertTrue(gate.shouldPost("Saving session #7", "STOP / Retry"))
        gate.reset()
        assertTrue(gate.shouldPost("Recording session #7", "STOP"))
    }
}
