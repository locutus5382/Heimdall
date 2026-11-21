package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.updateUnlockOrReleaseLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inject failures at the same update/release boundary used by WindowManager. */
@RunWith(AndroidJUnit4::class)
class HudUnlockUpdateGuardTest {
    @Test fun successfulUpdateDoesNotRemoveChipOrReleaseLock() {
        val calls = mutableListOf<String>()
        val success = updateUnlockOrReleaseLock(
            update = { calls += "update" },
            releaseWindow = { calls += "remove" },
            releaseLock = { calls += "unlock" }
        )
        assertTrue(success)
        assertEquals(listOf("update"), calls)
    }

    @Test fun rejectedUpdateRemovesStaleChipThenRestoresTouch() {
        val calls = mutableListOf<String>()
        val success = updateUnlockOrReleaseLock(
            update = { calls += "update"; throw IllegalArgumentException("detached") },
            releaseWindow = { calls += "remove" },
            releaseLock = { calls += "unlock" }
        )
        assertFalse(success)
        assertEquals(listOf("update", "remove", "unlock"), calls)
    }

    @Test fun cleanupFailureStillReleasesCanvasLock() {
        val calls = mutableListOf<String>()
        try {
            updateUnlockOrReleaseLock(
                update = { calls += "update"; throw IllegalArgumentException("detached") },
                releaseWindow = { calls += "remove"; throw IllegalStateException("remove rejected") },
                releaseLock = { calls += "unlock" }
            )
        } catch (_: IllegalStateException) {
            // Cleanup failure is surfaced, but restoring Canvas touch is mandatory.
        }
        assertEquals(listOf("update", "remove", "unlock"), calls)
    }
}
