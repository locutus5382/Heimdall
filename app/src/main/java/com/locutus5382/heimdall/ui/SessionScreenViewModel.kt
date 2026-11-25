package com.locutus5382.heimdall.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locutus5382.heimdall.engine.duko.DukoFrequencyCapAssessment
import com.locutus5382.heimdall.engine.duko.DukoWindow
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
    // Legacy test doubles may implement only recent(); production overrides
    // this bounded lookup to expand the local archive incrementally.
    suspend fun recent(limit: Int): List<SavedSession> = recent()
    suspend fun details(id: Long): SessionDetail
    suspend fun exportCsv(id: Long, uri: Uri): Long
    suspend fun analyzeWindow(id: Long, window: DukoWindow): DukoFoundationReport? = null
    suspend fun exportCsvWindow(id: Long, uri: Uri, window: DukoWindow): Long =
        error("SELECTED_CSV_NOT_SUPPORTED")
}

/** Android IO is delegated to SessionArchive, which switches to Dispatchers.IO. */
internal class SessionScreenRepository(context: Context) : SessionScreenDataSource {
    private val app = context.applicationContext
    private val archive = SessionArchive(app)
    private val tags = DukoSessionContextStore(app)

    override suspend fun recent(): List<SavedSession> = archive.recent()
    override suspend fun recent(limit: Int): List<SavedSession> = archive.recent(limit)
    override suspend fun details(id: Long): SessionDetail {
        val overview = archive.overview(id)
        val report = archive.dukoFoundation(id)
        val cap = runCatching {
            tags.deviceProfileJson(id)?.let(DukoDeviceCapture::assessSavedCpuPolicyCaps)
        }.getOrNull()
        return SessionDetail(overview, report, cap)
    }
    override suspend fun analyzeWindow(id: Long, window: DukoWindow): DukoFoundationReport? =
        archive.dukoFoundation(id, window)

    override suspend fun exportCsvWindow(id: Long, uri: Uri, window: DukoWindow): Long =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val stream = app.contentResolver.openOutputStream(uri, "wt")
                ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
            archive.exportCsv(id, stream, window)
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
    val hasMoreHistory: Boolean = false,
    val expandedId: Long? = null,
    val detail: SessionDetail? = null,
    val reading: Boolean = false,
    val notice: String? = null,
    val pendingExportId: Long? = null,
    val pendingExportWindow: DukoWindow? = null,
    val exporting: Boolean = false,
    val selectedAnalysis: DukoFoundationReport? = null,
    val analysisWindow: DukoWindow? = null,
    val readingSelection: Boolean = false,
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
    private var selectionJob: Job? = null
    private var selectionGeneration = 0L
    private var loadJob: Job? = null
    private var generation = 0L
    private var requestedFocusId: Long? = null
    private var historyLimit = 20

    /** An explicit touch action; never fetch all historical rows at startup. */
    fun loadOlder() {
        val current = mutable.value
        if (current.loadingHistory || !current.hasMoreHistory || historyLimit >= 100) return
        historyLimit = (historyLimit + 20).coerceAtMost(100)
        refresh(refreshKey ?: 0L, force = true)
    }

    /**
     * Open the exact newly finished session, even if a prior history list
     * was cached before the Service's durable STOP. The destination opens
     * only after the requested row appears in a refreshed SQLite snapshot.
     */
    fun focusSession(id: Long) {
        if (id <= 0L) return
        if (!mutable.value.loadingHistory && mutable.value.history.any { it.id == id }) {
            // Reusing the existing bounded archive avoids an unnecessary
            // SQLite refresh on every RUNS -> ANALYZE selection.
            requestedFocusId = null
            openDetails(id)
        } else {
            requestedFocusId = id
            refresh(id, force = true)
        }
    }

    /**
     * Entering ANALYZE with no chosen run should open the most recent one,
     * but must NEVER override an explicit RUNS selection still loading.
     */
    fun focusNewestIfNone() {
        val state = mutable.value
        if (requestedFocusId != null || state.loadingHistory ||
            state.expandedId != null) return
        state.history.firstOrNull()?.let { openDetails(it.id) }
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
                // Read one extra row (up to the repository's 100-row cap).
                // An exact full page does NOT prove another page exists.
                val retrieved = repo.recent((historyLimit + 1).coerceAtMost(100))
                val hasNext = historyLimit < 100 && retrieved.size > historyLimit
                val rows = retrieved.take(historyLimit)
                mutable.update { old ->
                    val stillExpanded = old.expandedId?.takeIf { id -> rows.any { it.id == id } }
                    old.copy(history = rows, loadingHistory = false,
                        hasMoreHistory = hasNext,
                        expandedId = stillExpanded,
                        detail = if (stillExpanded != null) old.detail else null,
                        selectedAnalysis = if (stillExpanded != null) old.selectedAnalysis else null,
                        analysisWindow = if (stillExpanded != null) old.analysisWindow else null,
                        readingSelection = if (stillExpanded != null) old.readingSelection else false,
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
            selectionGeneration++
            selectionJob?.cancel()
            mutable.update { it.copy(expandedId = null, detail = null, reading = false,
                selectedAnalysis = null, analysisWindow = null, readingSelection = false) }
        } else openDetails(id)
    }

    private fun openDetails(id: Long) {
        if (mutable.value.expandedId == id && mutable.value.detail != null) return
        generation++
        val request = generation
        detailJob?.cancel()
        selectionGeneration++
        selectionJob?.cancel()
        mutable.update { it.copy(expandedId = id, detail = null, reading = true,
            selectedAnalysis = null, analysisWindow = null,
            readingSelection = false, notice = null) }
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

    /**
     * Run the same bounded SQLite analytics over an explicitly selected
     * monotonic interval. Generation + session ownership prevent a stale
     * coroutine from publishing to another expanded recording.
     */
    fun selectAnalysisWindow(id: Long, window: DukoWindow?) {
        val state = mutable.value
        val full = state.detail?.duko?.window ?: return
        if (state.expandedId != id || state.exporting) return
        selectionGeneration++
        selectionJob?.cancel()
        if (window == null || window == full) {
            mutable.update { it.copy(analysisWindow = null,
                selectedAnalysis = null, readingSelection = false) }
            return
        }
        if (window.startMs < full.startMs || window.endMs > full.endMs) {
            mutable.update { it.copy(notice = "Analysis range lies outside saved evidence.") }
            return
        }
        val request = selectionGeneration
        val ownerGeneration = generation
        mutable.update { it.copy(analysisWindow = window,
            selectedAnalysis = null, readingSelection = true, notice = null) }
        selectionJob = viewModelScope.launch {
            try {
                val result = repo.analyzeWindow(id, window)
                if (generation == ownerGeneration && selectionGeneration == request &&
                    mutable.value.expandedId == id) {
                    mutable.update {
                        it.copy(selectedAnalysis = result, readingSelection = false,
                            notice = if (result == null)
                                "Selected interval lacks usable monotonic evidence."
                                else null)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                if (generation == ownerGeneration && selectionGeneration == request &&
                    mutable.value.expandedId == id) mutable.update {
                    it.copy(readingSelection = false,
                        notice = "Range analysis failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Returns whether a new SAF picker should open. No second export in flight. */
    fun requestExport(id: Long, selectedOnly: Boolean = false): Boolean {
        if (mutable.value.pendingExportId != null || mutable.value.exporting ||
            mutable.value.history.none { it.id == id }) return false
        val window = if (selectedOnly) {
            val state = mutable.value
            if (state.expandedId != id || state.readingSelection ||
                state.selectedAnalysis?.window != state.analysisWindow ||
                state.selectedAnalysis == null) return false
            state.analysisWindow ?: return false
        } else null
        mutable.update { it.copy(pendingExportId = id,
            pendingExportWindow = window, notice = null) }
        return true
    }

    fun onExportResult(destination: Uri?) {
        val id = mutable.value.pendingExportId
        val snapshotWindow = mutable.value.pendingExportWindow
        mutable.update { it.copy(pendingExportId = null, pendingExportWindow = null) }
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
                val count = if (snapshotWindow == null) repo.exportCsv(id, destination)
                    else repo.exportCsvWindow(id, destination, snapshotWindow)
                mutable.update { it.copy(notice = if (snapshotWindow == null)
                    "Session #$id exported: $count samples." else
                    "Session #$id selected range exported: $count original samples.") }
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
