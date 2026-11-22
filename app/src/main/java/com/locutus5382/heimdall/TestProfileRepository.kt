package com.locutus5382.heimdall

import android.content.Context
import android.content.SharedPreferences
import com.locutus5382.heimdall.recording.DukoSessionContextStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Read-only dashboard preview of the next-session, USER_DECLARED tags. */
data class TestProfilePreview(
    val title: String = "No test profile configured",
    val details: String = "Use Setup to declare game, driver and device conditions.",
    val userDeclared: Boolean = false,
    val nextOnly: Boolean = false,
)

class TestProfileRepository(context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("duko_next_session_v1", Context.MODE_PRIVATE)
    private val store = DukoSessionContextStore(app)
    private val mutable = MutableStateFlow(readPreview())
    val preview: StateFlow<TestProfilePreview> = mutable

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        mutable.value = readPreview()
    }

    private fun readPreview(): TestProfilePreview {
        val draft = store.readDraft()
        val declared = prefs.getBoolean("declared", false)
        if (!declared) return TestProfilePreview()
        val name = listOf(draft.game, draft.emulator).filter { it.isNotBlank() }
            .joinToString(" · ").ifBlank { "Unnamed test profile" }
        val tags = listOf(
            draft.graphicsDriver.takeIf { it.isNotBlank() }?.let { "Driver: $it" },
            draft.performanceMode.takeIf { it.isNotBlank() }?.let { "Mode: $it" },
            draft.fanProfile.takeIf { it.isNotBlank() }?.let { "Fan: $it" },
            draft.clusterTuneEnabled?.let {
                if (it) "ClusterTune: declared ON" else "ClusterTune: declared OFF"
            },
        ).filterNotNull()
        return TestProfilePreview(
            title = name,
            details = tags.joinToString(" · ").ifBlank { "No driver / mode / fan declared" },
            userDeclared = true,
            nextOnly = store.isNextOnly(),
        )
    }
}
