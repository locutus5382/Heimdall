package com.locutus5382.heimdall.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locutus5382.heimdall.engine.duko.DukoMultiRun
import com.locutus5382.heimdall.engine.duko.DukoMultiRunReport
import com.locutus5382.heimdall.engine.duko.DukoVisualDocument
import com.locutus5382.heimdall.engine.duko.DukoVisualReport
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.DukoVisualRenderer
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PendingDukoVisualExport(
    val format: DukoVisualRenderer.Format,
    val document: DukoVisualDocument,
)

internal interface MultiRunDataSource {
    suspend fun recent(): List<SavedSession>
    suspend fun bounds(id: Long): DukoWindow?
    suspend fun analyze(runs: List<Pair<Long, DukoWindow>>): DukoMultiRunReport?
    suspend fun writeVisual(
        destination: Uri, document: DukoVisualDocument,
        format: DukoVisualRenderer.Format,
    )
}

/** All heavy database scans and image/PDF rendering are off the main thread. */
internal class MultiRunRepository(context: Context) : MultiRunDataSource {
    private val app = context.applicationContext
    private val archive = SessionArchive(app)
    override suspend fun recent(): List<SavedSession> = archive.recent(100)
    override suspend fun bounds(id: Long): DukoWindow? = archive.dukoMonotonicBounds(id)
    override suspend fun analyze(runs: List<Pair<Long, DukoWindow>>): DukoMultiRunReport? =
        archive.dukoMultiRun(runs)
    override suspend fun writeVisual(
        destination: Uri, document: DukoVisualDocument,
        format: DukoVisualRenderer.Format,
    ): Unit = withContext(Dispatchers.IO) {
        val output = app.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
        output.use { DukoVisualRenderer.write(it, document, format) }
    }
}

internal data class MultiRunUiState(
    val sessions: List<SavedSession> = emptyList(),
    val loading: Boolean = false,
    val selected: List<Long> = emptyList(),
    val preset: AbWindowPreset = AbWindowPreset.FULL,
    val analyzed: DukoMultiRunReport? = null,
    val busy: Boolean = false,
    val issue: String? = null,
    val pending: PendingDukoVisualExport? = null,
    val writing: Boolean = false,
    val exportStatus: String? = null,
) {
    val canCompare: Boolean get() =
        selected.size in DukoMultiRun.MIN_RUNS..DukoMultiRun.MAX_RUNS &&
            selected.all { id -> sessions.any { it.id == id } } && !busy
}

/**
 * Isolated activity-owned multi-run state. The baseline is ALWAYS selected[0].
 * Changing a draft cancels work and invalidates stale analytical comparisons.
 */
internal class DukoMultiRunViewModel(private val repo: MultiRunDataSource) : ViewModel() {
    private val mutable = MutableStateFlow(MultiRunUiState())
    val ui: StateFlow<MultiRunUiState> = mutable
    private var loaded = false
    private var refreshKey: Long? = null
    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private var generation = 0L

    fun refresh(key: Long, force: Boolean = false) {
        if (!force && loaded && refreshKey == key) return
        if (mutable.value.pending != null || mutable.value.writing) return
        loaded = true
        refreshKey = key
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val rows = repo.recent().take(100)
                val ids = rows.map { it.id }.toSet()
                mutable.update { state ->
                    val selection = state.selected.filter { it in ids }
                    state.copy(sessions = rows, loading = false,
                        selected = selection,
                        analyzed = if (selection == state.selected) state.analyzed else null,
                        issue = null)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutable.update { it.copy(loading = false,
                    issue = "Saved sessions unavailable: " + error.javaClass.simpleName) }
            }
        }
    }

    fun reload() {
        if (mutable.value.pending != null || mutable.value.writing) return
        invalidate()
        refresh(refreshKey ?: 0L, force = true)
    }

    private fun invalidate() {
        generation++
        analysisJob?.cancel()
        mutable.update { it.copy(analyzed = null, busy = false,
            issue = null, exportStatus = null) }
    }

    fun toggle(id: Long) {
        val state = mutable.value
        if (id <= 0 || state.busy || state.writing || state.pending != null ||
            state.sessions.none { it.id == id }) return
        val selected = when {
            id in state.selected -> state.selected.filterNot { it == id }
            state.selected.size >= DukoMultiRun.MAX_RUNS -> {
                mutable.update { it.copy(issue = "Maximum six runs per comparison.") }
                return
            }
            else -> state.selected + id
        }
        invalidate()
        mutable.update { it.copy(selected = selected) }
    }

    fun baseline(id: Long) {
        val state = mutable.value
        if (id !in state.selected || state.busy || state.writing ||
            state.pending != null) return
        invalidate()
        mutable.update { it.copy(selected = listOf(id) + state.selected.filterNot { it == id }) }
    }

    fun cyclePreset() {
        val state = mutable.value
        if (state.busy || state.writing || state.pending != null) return
        val presets = listOf(AbWindowPreset.FULL, AbWindowPreset.MIDDLE_80,
            AbWindowPreset.LAST_50)
        val next = presets[(presets.indexOf(state.preset) + 1) % presets.size]
        invalidate()
        mutable.update { it.copy(preset = next) }
    }

    fun analyze() {
        val state = mutable.value
        if (!state.canCompare || state.writing || state.pending != null) return
        invalidate()
        val ticket = generation
        mutable.update { it.copy(busy = true, issue = null) }
        analysisJob = viewModelScope.launch {
            try {
                val windows = ArrayList<Pair<Long, DukoWindow>>(state.selected.size)
                for (id in state.selected) {
                    val bounds = repo.bounds(id)
                    val window = bounds?.let(state.preset::select)
                    if (window == null) {
                        if (generation == ticket) mutable.update {
                            it.copy(issue = "Session #" + id +
                                " lacks valid recorded monotonic bounds.")
                        }
                        return@launch
                    }
                    windows += id to window
                }
                val report = repo.analyze(windows)
                if (generation == ticket) mutable.update { it.copy(
                    analyzed = report,
                    issue = if (report == null)
                        "At least one selected interval lacks usable evidence."
                    else null)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (generation == ticket) mutable.update { it.copy(
                    issue = "Multi-run analysis failed: " + error.javaClass.simpleName) }
            } finally {
                if (generation == ticket) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun prepareVisual(format: DukoVisualRenderer.Format): String? {
        val state = mutable.value
        if (state.busy || state.writing || state.pending != null) return null
        val report = state.analyzed ?: return null
        val snapshot = DukoVisualReport.multi(report)
        mutable.update { it.copy(pending = PendingDukoVisualExport(format, snapshot),
            exportStatus = null) }
        val extension = if (format == DukoVisualRenderer.Format.PDF) ".pdf" else ".png"
        return "DUKO_MULTI_" + report.baseline.sessionId + "_" +
            report.sessionCount + "runs" + extension
    }

    fun onVisualResult(format: DukoVisualRenderer.Format, uri: Uri?) {
        val pending = mutable.value.pending
        if (pending == null || pending.format != format) {
            mutable.update { it.copy(exportStatus = "Export request expired.") }
            return
        }
        mutable.update { it.copy(pending = null) }
        if (uri == null) {
            mutable.update { it.copy(exportStatus = "Export cancelled.") }
            return
        }
        mutable.update { it.copy(writing = true,
            exportStatus = "Saving " + format.name + " report...") }
        viewModelScope.launch {
            try {
                repo.writeVisual(uri, pending.document, format)
                mutable.update { it.copy(exportStatus = format.name +
                    " comparison report saved locally.") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutable.update { it.copy(exportStatus =
                    "Visual export failed: " + error.javaClass.simpleName) }
            } finally {
                mutable.update { it.copy(writing = false) }
            }
        }
    }

    class Factory(private val repository: MultiRunDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DukoMultiRunViewModel::class.java))
            return DukoMultiRunViewModel(repository) as T
        }
    }
}
