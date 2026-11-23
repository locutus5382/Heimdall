package com.locutus5382.heimdall.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locutus5382.heimdall.engine.duko.DukoAbReportExport
import com.locutus5382.heimdall.engine.duko.DukoComparisonReport
import com.locutus5382.heimdall.engine.duko.DukoWindow
import com.locutus5382.heimdall.recording.SavedSession
import com.locutus5382.heimdall.recording.SessionArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface ComparisonDataSource {
    suspend fun recent(): List<SavedSession>
    suspend fun bounds(id: Long): DukoWindow?
    suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow): DukoComparisonReport?
    suspend fun write(uri: Uri, payload: String)
}

/** A/B is descriptive telemetry evidence, not FPS or an inferred cause. */
internal class ComparisonRepository(context: Context) : ComparisonDataSource {
    private val app = context.applicationContext
    private val archive = SessionArchive(app)
    override suspend fun recent(): List<SavedSession> = archive.recent()
    override suspend fun bounds(id: Long): DukoWindow? = archive.dukoMonotonicBounds(id)
    override suspend fun compare(a: Long, b: Long, wa: DukoWindow, wb: DukoWindow): DukoComparisonReport? =
        archive.dukoCompare(a, b, wa, wb)

    override suspend fun write(uri: Uri, payload: String) = withContext(Dispatchers.IO) {
        app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw java.io.IOException("DOCUMENT_PROVIDER_REFUSED_WRITE")
    }
}

internal enum class ComparisonExport { TXT, CSV }
internal data class PendingComparisonExport(val kind: ComparisonExport, val payload: String)

internal data class CompareUiState(
    val sessions: List<SavedSession> = emptyList(),
    val loading: Boolean = false,
    val a: Long? = null,
    val b: Long? = null,
    val presetA: AbWindowPreset = AbWindowPreset.FULL,
    val presetB: AbWindowPreset = AbWindowPreset.FULL,
    val startA: String = "0",
    val endA: String = "",
    val startB: String = "0",
    val endB: String = "",
    val report: DukoComparisonReport? = null,
    val selectedWindows: Pair<DukoWindow, DukoWindow>? = null,
    val busy: Boolean = false,
    val issue: String? = null,
    val exportStatus: String? = null,
    val pendingExport: PendingComparisonExport? = null,
    val writing: Boolean = false,
) {
    val canCompare: Boolean get() = a != null && b != null && a != b && !busy
}

/** Activity-scoped state survives tab switches and configuration recreation. */
internal class CompareScreenViewModel(private val repo: ComparisonDataSource) : ViewModel() {
    private val mutable = MutableStateFlow(CompareUiState())
    val ui: StateFlow<CompareUiState> = mutable
    private var loaded = false
    private var refreshKey: Long? = null
    private var listJob: Job? = null
    private var compareJob: Job? = null
    private var generation = 0L

    fun refresh(key: Long) {
        if (loaded && refreshKey == key) return
        loaded = true
        refreshKey = key
        listJob?.cancel()
        listJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val rows = repo.recent()
                val ids = rows.map { it.id }.toSet()
                mutable.update { s ->
                    val bothValid = (s.a == null || s.a in ids) &&
                        (s.b == null || s.b in ids)
                    s.copy(sessions = rows, loading = false, issue = null,
                        a = s.a?.takeIf { it in ids },
                        b = s.b?.takeIf { it in ids },
                        report = if (bothValid) s.report else null,
                        selectedWindows = if (bothValid) s.selectedWindows else null)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                mutable.update { it.copy(loading = false,
                    issue = "Saved runs unavailable: ${e.javaClass.simpleName}") }
            }
        }
    }

    private fun changeDraft(change: (CompareUiState) -> CompareUiState) {
        generation++
        compareJob?.cancel()
        mutable.update {
            change(it).copy(report = null, selectedWindows = null,
                busy = false, issue = null, exportStatus = null)
        }
    }

    fun selectA(id: Long) = changeDraft { it.copy(a = id) }
    fun selectB(id: Long) = changeDraft { it.copy(b = id) }
    fun cycleA() = changeDraft { s ->
        s.copy(presetA = AbWindowPreset.entries[(s.presetA.ordinal + 1) %
            AbWindowPreset.entries.size])
    }
    fun cycleB() = changeDraft { s ->
        s.copy(presetB = AbWindowPreset.entries[(s.presetB.ordinal + 1) %
            AbWindowPreset.entries.size])
    }
    fun setStartA(v: String) = changeDraft { it.copy(startA = v) }
    fun setEndA(v: String) = changeDraft { it.copy(endA = v) }
    fun setStartB(v: String) = changeDraft { it.copy(startB = v) }
    fun setEndB(v: String) = changeDraft { it.copy(endB = v) }

    fun clear() = changeDraft {
        it.copy(a = null, b = null, presetA = AbWindowPreset.FULL,
            presetB = AbWindowPreset.FULL,
            startA = "0", endA = "", startB = "0", endB = "")
    }

    fun compare() {
        val request = mutable.value
        val a = request.a ?: return
        val b = request.b ?: return
        if (!request.canCompare) return
        generation++
        val ticket = generation
        compareJob?.cancel()
        compareJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, issue = null,
                report = null, selectedWindows = null) }
            try {
                val boundsA = repo.bounds(a)
                val boundsB = repo.bounds(b)
                val wa = boundsA?.let {
                    if (request.presetA == AbWindowPreset.CUSTOM)
                        customAbWindow(it, request.startA, request.endA)
                    else request.presetA.select(it)
                }
                val wb = boundsB?.let {
                    if (request.presetB == AbWindowPreset.CUSTOM)
                        customAbWindow(it, request.startB, request.endB)
                    else request.presetB.select(it)
                }
                if (wa == null || wb == null) {
                    if (generation == ticket) mutable.update { it.copy(
                        issue = "Invalid analysis window: check recorded bounds and start/end seconds.") }
                } else {
                    val report = repo.compare(a, b, wa, wb)
                    if (generation == ticket) mutable.update { it.copy(
                        report = report,
                        selectedWindows = if (report == null) null else wa to wb,
                        issue = if (report == null)
                            "Selected windows have insufficient monotonic evidence." else null
                    ) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                if (generation == ticket) mutable.update { it.copy(
                    issue = "Comparison unavailable: ${e.javaClass.simpleName}") }
            } finally {
                if (generation == ticket) mutable.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Freeze the exact content BEFORE the SAF picker opens. Changing runs or
     * analysis windows never silently changes the bytes being exported.
     */
    fun prepareExport(kind: ComparisonExport): String? {
        val state = mutable.value
        if (state.pendingExport != null || state.writing) return null
        val report = state.report ?: return null
        val windows = state.selectedWindows ?: return null
        val payload = when (kind) {
            ComparisonExport.TXT -> DukoAbReportExport.text(report, windows.first, windows.second)
            ComparisonExport.CSV -> DukoAbReportExport.csv(report, windows.first, windows.second)
        }
        mutable.update { it.copy(pendingExport = PendingComparisonExport(kind, payload),
            exportStatus = null) }
        return "DUKO_AB_${report.sessionA}_${report.sessionB}." +
            if (kind == ComparisonExport.TXT) "txt" else "csv"
    }

    fun onExportResult(kind: ComparisonExport, uri: Uri?) {
        val pending = mutable.value.pendingExport
        if (pending == null || pending.kind != kind) {
            mutable.update { it.copy(exportStatus = "Export request expired; no file was written.") }
            return
        }
        mutable.update { it.copy(pendingExport = null) }
        if (uri == null) {
            mutable.update { it.copy(exportStatus = "Export cancelled.") }
            return
        }
        // Mark the export busy synchronously so another picker cannot open
        // before the first writer coroutine gets CPU time.
        mutable.update { it.copy(writing = true, exportStatus = "Saving ${kind.name}…") }
        viewModelScope.launch {
            try {
                repo.write(uri, pending.payload)
                mutable.update { it.copy(exportStatus = "${kind.name} report saved locally.") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                mutable.update { it.copy(exportStatus =
                    "Export failed: ${e.javaClass.simpleName}") }
            } finally {
                mutable.update { it.copy(writing = false) }
            }
        }
    }

    class Factory(private val repository: ComparisonDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CompareScreenViewModel::class.java))
            return CompareScreenViewModel(repository) as T
        }
    }
}
