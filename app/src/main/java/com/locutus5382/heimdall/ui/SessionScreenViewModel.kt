package com.locutus5382.heimdall.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapAssessment
import com.locutus5382.heimdall.recording.DukoDeviceCapture
import com.locutus5382.heimdall.recording.DukoFoundationReport
import com.locutus5382.heimdall.recording.DukoSessionContextStore
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import com.locutus5382.heimdall.recording.SessionOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android IO is delegated to SessionArchive, which switches to Dispatchers.IO. */
internal class SessionScreenRepository(context: Context) {
    private val app = context.applicationContext
    private val archive = SessionArchive(app)
    private val tags = DukoSessionContextStore(app)

    suspend fun recent(): List<SavedSession> = archive.recent()
    suspend fun details(id: Long): SessionDetail {
        val overview = archive.overview(id)
        val report = archive.dukoFoundation(id)
        val cap = runCatching {
            tags.deviceProfileJson(id)?.let(DukoDeviceCapture::assessSavedCpuPolicyCaps)
        }.getOrNull()
        return SessionDetail(overview, report, cap)
    }
    suspend fun exportCsv(id: Long, uri: Uri): Long {
        // CreateDocument is SAF; opening the stream is best-effort and the
        // provider may refuse writes even after a user selects a destination.
        val stream = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
        return archive.exportCsv(id, stream)
    }
}
internal data class SessionDetail(
    val overview: SessionOverview,
    val duko: DukoFoundationReport?,
    val caps: DukoFrequencyCapAssessment?,
)
internal data class SessionsUiState(
    val history: List<SavedSession> = emptyList(),
    val loadingHistory: Boolean = false,
    val expandedId: Long? = null,
    val detail: SessionDetail? = null,
    val reading: Boolean = false,
    val notice: String? = null,
    val pendingExportId: Long? = null,
    val exporting: Boolean = false,
)

/**
 * Activity-scoped so a tab change or device rotation cannot detach an analysis
 * result from the session that owns it. New requests cancel old work.
 */
internal class SessionScreenViewModel(private val repo: SessionScreenRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SessionsUiState())
    val ui: StateFlow<SessionsUiState> = mutable
    private var loaded = false
    private var refreshKey: Long? = null
    private var detailJob: Job? = null
    private var loadJob: Job? = null
    private var generation = 0L

    fun refresh(key: Long) {
        if (loaded && refreshKey == key) return
        loaded = true
        refreshKey = key
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutable.update { it.copy(loadingHistory = true) }
            try {
                val rows = repo.recent()
                mutable.update { old ->
                    val stillExpanded = old.expandedId?.takeIf { id -> rows.any { it.id == id } }
                    old.copy(history = rows, loadingHistory = false,
                        expandedId = stillExpanded,
                        detail = if (stillExpanded != null) old.detail else null,
                        notice = null)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(loadingHistory = false,
                    notice = "Saved sessions unavailable: ${e.javaClass.simpleName}") }
            }
        }
    }

    fun toggle(id: Long) {
        generation++
        val request = generation
        detailJob?.cancel()
        if (mutable.value.expandedId == id) {
            mutable.update { it.copy(expandedId = null, detail = null, reading = false) }
            return
        }
        mutable.update { it.copy(expandedId = id, detail = null, reading = true, notice = null) }
        detailJob = viewModelScope.launch {
            try {
                val item = repo.details(id)
                if (generation == request && mutable.value.expandedId == id)
                    mutable.update { it.copy(detail = item, reading = false) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (generation == request && mutable.value.expandedId == id)
                    mutable.update { it.copy(reading = false,
                        notice = "Summary unavailable: ${e.javaClass.simpleName}") }
            }
        }
    }

    /** Returns whether a new SAF picker should open. No second export in flight. */
    fun requestExport(id: Long): Boolean {
        if (mutable.value.pendingExportId != null || mutable.value.exporting ||
            mutable.value.history.none { it.id == id }) return false
        mutable.update { it.copy(pendingExportId = id, notice = null) }
        return true
    }

    fun onExportResult(destination: Uri?) {
        val id = mutable.value.pendingExportId
        mutable.update { it.copy(pendingExportId = null) }
        if (destination == null) {
            mutable.update { it.copy(notice = if (id == null)
                "Export request expired; select a session again." else "CSV export cancelled.") }
            return
        }
        if (id == null) {
            mutable.update { it.copy(notice = "Export request expired; no session was written.") }
            return
        }
        viewModelScope.launch {
            mutable.update { it.copy(exporting = true) }
            try {
                val count = repo.exportCsv(id, destination)
                mutable.update { it.copy(notice = "Session #$id exported: $count samples.") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                mutable.update { it.copy(notice = "CSV export failed: ${e.javaClass.simpleName}") }
            } finally {
                mutable.update { it.copy(exporting = false) }
            }
        }
    }

    class Factory(private val repository: SessionScreenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SessionScreenViewModel::class.java))
            return SessionScreenViewModel(repository) as T
        }
    }
}
