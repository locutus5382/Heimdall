package com.locutus5382.heimdall.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locutus5382.heimdall.recording.DiagnosticSnapshot
import com.locutus5382.heimdall.recording.RuntimeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/** Diagnostics observes local device capability; it never owns a recorder. */
internal interface DiagnosticsDataSource {
    suspend fun capture(): DiagnosticSnapshot
    suspend fun save(uri: Uri, text: String)
}

internal class AndroidDiagnosticsRepository(context: Context) : DiagnosticsDataSource {
    private val app = context.applicationContext
    private val inspector = RuntimeDiagnostics(app)
    override suspend fun capture(): DiagnosticSnapshot = inspector.capture()
    override suspend fun save(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val output = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw java.io.IOException("DESTINATION_UNAVAILABLE")
        output.use {
            it.write(text.toByteArray(StandardCharsets.UTF_8))
            it.flush()
        }
    }
}

internal data class DiagnosticsUiState(
    val snapshot: DiagnosticSnapshot? = null,
    val inspecting: Boolean = false,
    val pendingExportText: String? = null,
    val writing: Boolean = false,
    val message: String? = null,
)

/**
 * Activity-scoped, so the same results are available after a blade closes,
 * a tab changes or a rotation occurs. Exports freeze immutable text before
 * Android's SAF file picker opens; lost state fails closed.
 */
internal class DiagnosticsViewModel(private val source: DiagnosticsDataSource) : ViewModel() {
    private val mutable = MutableStateFlow(DiagnosticsUiState())
    val ui: StateFlow<DiagnosticsUiState> = mutable

    fun inspect() {
        if (mutable.value.inspecting || mutable.value.writing ||
            mutable.value.pendingExportText != null) return
        mutable.update { it.copy(inspecting = true, message = null) }
        viewModelScope.launch {
            try {
                val result = source.capture()
                mutable.update { it.copy(snapshot = result, message = "Inspection complete.") }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(message =
                    "Inspection failed: ${e.javaClass.simpleName}") }
            } finally {
                mutable.update { it.copy(inspecting = false) }
            }
        }
    }

    /** Return a document name only if an export picker can safely open. */
    fun requestExport(): String? {
        val s = mutable.value
        if (s.inspecting || s.writing || s.pendingExportText != null) return null
        val frozen = s.snapshot?.asText() ?: return null
        mutable.update { it.copy(pendingExportText = frozen, message = null) }
        return "Heimdall_device_diagnostics.txt"
    }

    fun onExportResult(destination: Uri?) {
        val data = mutable.value.pendingExportText
        mutable.update { it.copy(pendingExportText = null) }
        if (data == null) {
            mutable.update { it.copy(message =
                "Export request expired; no diagnostic file was written.") }
            return
        }
        if (destination == null) {
            mutable.update { it.copy(message = "Diagnostic export cancelled.") }
            return
        }
        // Lock synchronously, before the coroutine starts, to prevent
        // competing picker/export requests.
        mutable.update { it.copy(writing = true, message = "Saving diagnostic report…") }
        viewModelScope.launch {
            try {
                source.save(destination, data)
                mutable.update { it.copy(message = "Diagnostic report saved locally.") }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(message =
                    "Diagnostic export failed: ${e.javaClass.simpleName}") }
            } finally {
                mutable.update { it.copy(writing = false) }
            }
        }
    }

    class Factory(private val source: DiagnosticsDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DiagnosticsViewModel::class.java))
            return DiagnosticsViewModel(source) as T
        }
    }
}
