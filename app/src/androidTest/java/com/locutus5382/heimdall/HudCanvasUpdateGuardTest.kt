package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.updateCanvasOrRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inject failures into the Canvas update boundary used by the HUD controller. */
@RunWith(AndroidJUnit4::class)
class HudCanvasUpdateGuardTest {
    @Test fun successfulUpdateRetainsCanvasOwnership() {
        val calls = mutableListOf<String>()
        val result = updateCanvasOrRelease(
            update = { calls += "update" },
            release = { calls += "release" }
        )
        assertTrue(result)
        assertEquals(listOf("update"), calls)
    }

    @Test fun rejectedUpdateReleasesCanvasAndUnlockOwnership() {
        val calls = mutableListOf<String>()
        val result = updateCanvasOrRelease(
            update = { calls += "update"; throw IllegalArgumentException("detached") },
            release = {
                assertTrue(it is IllegalArgumentException)
                calls += "release"
            }
        )
        assertFalse(result)
        assertEquals(listOf("update", "release"), calls)
    }

    @Test fun failureDoesNotAttemptAnotherUpdateInSameInvocation() {
        var updates = 0
        var releases = 0
        val result = updateCanvasOrRelease(
            update = { updates++; throw SecurityException("overlay permission revoked") },
            release = { releases++ }
        )
        assertFalse(result)
        assertEquals(1, updates)
        assertEquals(1, releases)
    }
}
