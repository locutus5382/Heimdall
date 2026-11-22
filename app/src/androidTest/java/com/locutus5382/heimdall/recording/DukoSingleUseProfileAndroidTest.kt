package com.locutus5382.heimdall.recording

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated preferences policy tests; no real recording or sysfs mutations. */
@RunWith(AndroidJUnit4::class)
class DukoSingleUseProfileAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = app.getSharedPreferences(
        "duko_next_session_v1", Context.MODE_PRIVATE
    )
    private val store get() = DukoSessionContextStore(app)

    @After fun reset() {
        prefs.edit().clear().commit()
    }

    @Test fun oneRunTagIsClearedOnlyAfterMatchingCommittedRevision() {
        val initial = store.readDraft().copy(game = "Test A")
        store.saveDraft(initial, nextOnly = true)
        assertTrue(store.isNextOnly())
        val oldPrepared = DukoSessionContextStore.PreparedSessionMetadata(
            context = store.readDraft(), contractVersion = 0,
            capturedAtMs = 0L, profileJson = "{}",
            nextOnlyRevision = prefs.getLong("draft_revision", 0L)
        )
        // A user modifies the draft while the earlier hardware probe runs.
        store.saveDraft(initial.copy(game = "Test B"), nextOnly = true)
        store.consumeNextOnlyAfterCommit(oldPrepared)
        assertEquals("Test B", store.readDraft().game)
        assertTrue(store.isNextOnly())

        val current = DukoSessionContextStore.PreparedSessionMetadata(
            context = store.readDraft(), contractVersion = 0,
            capturedAtMs = 0L, profileJson = "{}",
            nextOnlyRevision = prefs.getLong("draft_revision", 0L)
        )
        store.consumeNextOnlyAfterCommit(current)
        assertFalse(store.isNextOnly())
        assertEquals("", store.readDraft().game)
    }

    @Test fun reusableTagSurvivesCommittedSession() {
        val draft = store.readDraft().copy(game = "Reusable")
        store.saveDraft(draft, nextOnly = false)
        val prepared = DukoSessionContextStore.PreparedSessionMetadata(
            context = store.readDraft(), contractVersion = 0,
            capturedAtMs = 0L, profileJson = "{}", nextOnlyRevision = null
        )
        store.consumeNextOnlyAfterCommit(prepared)
        assertEquals("Reusable", store.readDraft().game)
        assertFalse(store.isNextOnly())
    }
}
