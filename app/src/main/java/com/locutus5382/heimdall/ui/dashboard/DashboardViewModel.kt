package com.locutus5382.heimdall.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locutus5382.heimdall.HeimdallAppGraph
import com.locutus5382.heimdall.HudPreferenceRepository
import com.locutus5382.heimdall.RecorderRuntime
import com.locutus5382.heimdall.engine.RecorderStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One immutable dashboard projection. Recording status is never created here:
 * the native engine -> RecorderStatusHub flow is authoritative for app, HUD
 * and Quick Settings. An Android intent is only a REQUEST, not a success.
 */
data class DashboardUiState(
    val status: RecorderStatus = RecorderStatus.Idle,
    val hudEnabled: Boolean = false,
    val overlayPermission: Boolean = false,
    val commandPending: Boolean = false,
    val issue: String? = null,
)

private enum class PendingCommand { START, STOP }

class DashboardViewModel(
    private val runtime: RecorderRuntime,
    private val hud: HudPreferenceRepository,
) : ViewModel() {
    private val overlay = MutableStateFlow(false)
    private val issue = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow<PendingCommand?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        runtime.status, hud.enabled, overlay, issue, pending
    ) { status, hudEnabled, overlayPermission, currentIssue, command ->
        DashboardUiState(status, hudEnabled, overlayPermission,
            commandPending = command != null, issue = currentIssue)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        DashboardUiState(status = runtime.status.value, hudEnabled = hud.enabled.value)
    )

    init {
        viewModelScope.launch {
            runtime.status.collect { state ->
                when (pending.value) {
                    PendingCommand.START -> if (
                        state is RecorderStatus.Preparing ||
                        state is RecorderStatus.Recording ||
                        state is RecorderStatus.NeedsAttention
                    ) {
                        pending.value = null
                    }
                    PendingCommand.STOP -> if (
                        state is RecorderStatus.Stopping ||
                        state is RecorderStatus.Saving ||
                        state is RecorderStatus.Finished ||
                        state is RecorderStatus.NeedsAttention
                    ) {
                        pending.value = null
                    }
                    null -> Unit
                }
            }
        }
    }

    fun inspect() {
        runtime.inspectOnce()?.let { issue.value = it }
    }

    fun onOverlayPermissionChanged(granted: Boolean) {
        overlay.value = granted
    }

    /** Returns true if enabling requires the Activity to request a grant. */
    fun toggleHud(): Boolean {
        val next = !hud.enabled.value
        hud.setEnabled(next)
        return next && !overlay.value
    }

    fun reportIssue(message: String) { issue.value = message }

    /** True only if a START intent was dispatched (not yet acknowledged). */
    fun start(): Boolean {
        val state = runtime.status.value
        val allowed = state is RecorderStatus.Idle ||
            state is RecorderStatus.Finished ||
            (state is RecorderStatus.NeedsAttention && state.sessionId == null)
        if (!allowed || pending.value != null) return false
        return dispatch(PendingCommand.START) { runtime.start() }
    }

    fun stop() {
        val state = runtime.status.value
        val allowed = state is RecorderStatus.Recording ||
            (state is RecorderStatus.NeedsAttention && state.sessionId != null)
        if (!allowed || pending.value != null) return
        dispatch(PendingCommand.STOP) { runtime.stop() }
    }

    private fun dispatch(command: PendingCommand, send: () -> String?): Boolean {
        issue.value = null
        pending.value = command
        val error = send()
        if (error != null) {
            pending.value = null
            issue.value = error
            return false
        }
        viewModelScope.launch {
            delay(8_000L)
            if (pending.value == command) {
                pending.value = null
                issue.value = "Recorder has not acknowledged the request; check status before retrying."
            }
        }
        return true
    }

    class Factory(private val graph: HeimdallAppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DashboardViewModel::class.java))
            return DashboardViewModel(graph.recorder, graph.hudPreferences) as T
        }
    }
}
