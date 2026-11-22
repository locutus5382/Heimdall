package com.locutus5382.heimdall.ui.dashboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.HudPreferenceRepository
import com.locutus5382.heimdall.RecorderRuntime
import com.locutus5382.heimdall.TestProfileRepository
import com.locutus5382.heimdall.engine.RecorderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One command path: the Dashboard cannot start a second engine. */
@RunWith(AndroidJUnit4::class)
class DashboardRuntimeAndroidTest {
    private class FakeRecorder : RecorderRuntime {
        val states = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
        override val status: StateFlow<RecorderStatus> = states
        var starts = 0
        var stops = 0
        override fun start(): String? { starts++; return null }
        override fun stop(): String? { stops++; return null }
        override fun inspectOnce(): String? = null
    }

    @Test fun pendingStartBlocksDoubleTapUntilRecorderAcknowledges() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        val fake = FakeRecorder()
        val model = DashboardViewModel(
            fake, HudPreferenceRepository(app), TestProfileRepository(app)
        )
        assertTrue(model.start())
        assertFalse(model.start())
        assertEquals(1, fake.starts)
        instrument.runOnMainSync {
            fake.states.value = RecorderStatus.Recording(41L, persistedSamples = 1L)
        }
        instrument.waitForIdleSync()
        assertFalse(model.uiState.value.commandPending)
        model.stop()
        model.stop()
        assertEquals(1, fake.stops)
    }

    @Test fun unfinishedSessionIsNeverReplacedByStart() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val fake = FakeRecorder()
        fake.states.value = RecorderStatus.NeedsAttention(9L, "RECOVERY_REQUIRED")
        val model = DashboardViewModel(fake,
            HudPreferenceRepository(instrument.targetContext),
            TestProfileRepository(instrument.targetContext))
        assertFalse(model.start())
        assertEquals(0, fake.starts)
        model.stop()
        assertEquals(1, fake.stops)
    }
}
