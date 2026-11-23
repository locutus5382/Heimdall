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

internal interface SessionScreenDataSource {
    suspend fun recent(): List<SavedSession>
    suspend fun details(id: Long): SessionDetail
    suspend fun exportCsv(id: Long, uri: Uri): Long
}

/** Android IO is delegated to SessionArchive, which switches to Dispatchers.IO. */
internal class SessionScreenRepository(context: Context) : SessionScreenDataSource {
    private val app = context.applicationContext
    private val archive = SessionArchive(app)
    private val tags = DukoSessionContextStore(app)

    override suspend fun recent(): List<SavedSession> = archive.recent()
    override suspend fun details(id: Long): SessionDetail {
        val overview = archive.overview(id)
        val report = archive.dukoFoundation(id)
        val cap = runCatching {
            tags.deviceProfileJson(id)?.let(DukoDeviceCapture::assessSavedCpuPolicyCaps)
        }.getOrNull()
        return SessionDetail(overview, report, cap)
    }
    override suspend fun exportCsv(id: Long, uri: Uri): Long =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Provider open can block too; do not perform any SAF operations on Main.
            val stream = app.contentResolver.openOutputStream(uri, "wt")
                ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
            archive.exportCsv(id, stream)
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
internal class SessionScreenViewModel(private val repo: SessionScreenDataSource) : ViewModel() {
    private val mutable = MutableStateFlow(SessionsUiState())
    val ui: StateFlow<SessionsUiState> = mutable
    private var loaded = false
    private var refreshKey: Long? = null
    private var detailJob: Job? = null
    private var loadJob: Job? = null
    private var generation = 0L
    private var requestedFocusId: Long? = null

    /**
     * Open the exact newly finished session, even if a prior history list
     * was cached before the Service's durable STOP. The destination opens
     * only after the requested row appears in a refreshed SQLite snapshot.
     */
    fun focusSession(id: Long) {
        if (id <= 0L) return
        requestedFocusId = id
        refresh(id, force = true)
    }

    /** Manual reload for sessions saved by the HUD/Quick Settings while open. */
    fun reload() = refresh(refreshKey ?: 0L, force = true)

    fun refresh(key: Long, force: Boolean = false) {
        if (!force && loaded && refreshKey == key) return
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
                val focused = requestedFocusId
                if (focused != null) {
                    requestedFocusId = null
                    if (rows.any { it.id == focused }) {
                        openDetails(focused)
                    } else {
                        mutable.update { it.copy(notice =
                            "Saved session #$focused is not in the archive yet. Refresh to retry.") }
                    }
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
        if (id <= 0L || mutable.value.history.none { it.id == id }) return
        if (mutable.value.expandedId == id) {
            generation++
            detailJob?.cancel()
            mutable.update { it.copy(expandedId = null, detail = null, reading = false) }
        } else openDetails(id)
    }

    private fun openDetails(id: Long) {
        if (mutable.value.expandedId == id && mutable.value.detail != null) return
        generation++
        val request = generation
        detailJob?.cancel()
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
        // Block another SAF request before coroutine dispatch, including
        // a rapid second tap during the first main-loop iteration.
        mutable.update { it.copy(exporting = true, notice = "Exporting CSV…") }
        viewModelScope.launch {
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

    class Factory(private val repository: SessionScreenDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SessionScreenViewModel::class.java))
            return SessionScreenViewModel(repository) as T
        }
    }
}
