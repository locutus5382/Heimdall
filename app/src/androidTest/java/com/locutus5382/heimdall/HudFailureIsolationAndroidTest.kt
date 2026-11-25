package com.locutus5382.heimdall

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import com.locutus5382.heimdall.engine.RecorderStatus
import com.locutus5382.heimdall.recording.rollbackHudWindow
import com.locutus5382.heimdall.recording.safelyDeliverHud
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual production HUD failure boundaries, not a second
 * imitation recorder. Physical WindowManager overlay permission, attachment,
 * rotation and input behavior remain Odin-specific acceptance gates.
 */
@RunWith(AndroidJUnit4::class)
class HudFailureIsolationAndroidTest {
    @Test fun brokenHudDoesNotBlockLaterRecordingAndStopStatusDelivery() {
        val received = mutableListOf<String>()
        var released = 0
        var reported = 0
        val statuses = listOf(
            RecorderStatus.Recording(81L, 1L),
            RecorderStatus.Recording(81L, 2L),
            RecorderStatus.Stopping(81L),
            RecorderStatus.Saving(81L),
            RecorderStatus.Finished(81L, 2L),
        )
        statuses.forEachIndexed { index, state ->
            // Same ordering as Service: the canonical in-process status
            // gets published regardless of any Canvas failure.
            received += state::class.java.simpleName
            val ok = safelyDeliverHud(
                render = {
                    if (index == 0) error("Canvas failure")
                },
                release = { released++ },
                report = { reported++ },
            )
            assertEquals(index != 0, ok)
            // These operations represent the downstream notification/Tile
            // handoff which must still run after the failed HUD.
            received += "NOTIFIED"
        }
        assertEquals(1, released)
        assertEquals(1, reported)
        assertEquals(5, received.count { it == "NOTIFIED" })
        assertEquals("Finished", received[8])
        assertEquals("NOTIFIED", received[9])
    }

    @Test fun failingHudCleanupOrLoggerCannotBreakStatusDelivery() {
        val failures = mutableListOf<String>()
        repeat(2) {
            val result = safelyDeliverHud(
                render = { error("WindowManager update rejected") },
                release = { failures += "remove"; error("Removal rejected") },
                report = { failures += "log"; error("Logging unavailable") },
            )
            assertFalse(result)
        }
        assertEquals(listOf("remove", "log", "remove", "log"), failures)
    }

    @Test fun coroutineCancellationIsNeverSwallowedAsAHudFailure() {
        var releaseCount = 0
        var reportCount = 0
        try {
            safelyDeliverHud(
                render = { throw CancellationException("Service ending") },
                release = { releaseCount++ },
                report = { reportCount++ },
            )
            fail("Coroutine cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, releaseCount)
            assertEquals(0, reportCount)
        }
    }

    @Test fun rollbackAttemptsRemovalEvenBeforeViewSignalsAttached() {
        // No isAttachedToWindow guard: vendor attach callbacks can be delayed
        // even though WindowManager already owns the token.
        val operations = mutableListOf<String>()
        val result = rollbackHudWindow(
            remove = { operations += "removeViewImmediate" },
            report = { operations += "error" },
        )
        assertTrue(result)
        assertEquals(listOf("removeViewImmediate"), operations)
    }

    @Test fun rejectedRollbackDoesNotCrashRecorder() {
        val operations = mutableListOf<String>()
        val result = rollbackHudWindow(
            remove = {
                operations += "remove"
                throw IllegalArgumentException("not attached")
            },
            report = { operations += "diagnostic" },
        )
        assertFalse(result)
        assertEquals(listOf("remove", "diagnostic"), operations)
    }
}
