package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.shouldAttachHudUnlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HudUnlockOwnershipTest {
    @Test fun lockedWithoutCanvasCannotCreateOrphanUnlockChip() {
        assertFalse(shouldAttachHudUnlock(canvasOwned = false, locked = true))
    }

    @Test fun unlockedCanvasDoesNotNeedUnlockChip() {
        assertFalse(shouldAttachHudUnlock(canvasOwned = true, locked = false))
    }

    @Test fun lockedCanvasCanOfferUnlockChip() {
        assertTrue(shouldAttachHudUnlock(canvasOwned = true, locked = true))
    }
}
