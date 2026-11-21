package app.nolva.ui.document

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageImporter
import app.nolva.data.PaperSize
import app.nolva.data.PdfExport
import app.nolva.data.PdfExporter
import app.nolva.data.pdfFileName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DocumentDetailUiState(
    val isLoading: Boolean = true,
    val pages: List<Page> = emptyList(),
    /** Number of images still being imported. */
    val pendingImports: Int = 0,
    val message: DocumentDetailMessage? = null,
    val isExporting: Boolean = false,
    /** A finished export waiting to be handed to the share sheet. */
    val pendingShare: PdfExport? = null,
) {
    val isImporting: Boolean get() = pendingImports > 0
}

enum class DocumentDetailMessage {
    LoadFailed,
    ImportFailed,
    UnsupportedImage,
    ExportFailed,
    DeletePageFailed,
    MovePageFailed,
}

/** Exports older than this are deleted before a new export. */
private const val EXPORT_MAX_AGE_MS = 24L * 60 * 60 * 1000

class DocumentDetailViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
    private val editor: DocumentEditor,
    private val importer: PageImporter,
    private val exporter: PdfExporter,
    private val exportDir: File,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    /**
     * Serializes page edits: each move returns the full new order, so two
     * overlapping moves could otherwise apply their results out of order.
     */
    private val editMutex = Mutex()

    /** Reloads the pages, e.g. when coming back from the camera. */
    fun refresh() {
        viewModelScope.launch {
            val result = runSuspendCatching { repository.listPages(documentId) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(isLoading = false, pages = it) },
                    onFailure = { state.copy(isLoading = false, message = DocumentDetailMessage.LoadFailed) },
                )
            }
        }
    }

    /**
     * Imports images in the order given, one at a time, so pages keep the
     * order the user picked them in.
     */
    fun importImages(uris: List<String>) {
        if (uris.isEmpty()) return
        _uiState.update { it.copy(pendingImports = it.pendingImports + uris.size) }
        viewModelScope.launch {
            for (uri in uris) {
                val result = runSuspendCatching { importer.import(documentId, uri) }
                _uiState.update { state ->
                    val remaining = state.pendingImports - 1
                    result.fold(
                        onSuccess = { page -> state.copy(pendingImports = remaining, pages = state.pages + page) },
                        onFailure = { error ->
                            val message = if (error is DocumentException.Unsupported) {
                                DocumentDetailMessage.UnsupportedImage
                            } else {
                                DocumentDetailMessage.ImportFailed
                            }
                            state.copy(pendingImports = remaining, message = message)
                        },
                    )
                }
            }
        }
    }

    /** Permanently deletes a page; the following pages move up. */
    fun deletePage(pageId: String) {
        viewModelScope.launch {
            val result = editMutex.withLock { runSuspendCatching { editor.deletePage(pageId) } }
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        val remaining = state.pages.filterNot { it.id == pageId }
                        state.copy(pages = remaining.mapIndexed { index, page -> page.copy(position = index) })
                    },
                    onFailure = { state.copy(message = DocumentDetailMessage.DeletePageFailed) },
                )
            }
        }
    }

    /** Moves a page to [toPosition] (zero-based). */
    fun movePage(pageId: String, toPosition: Int) {
        viewModelScope.launch {
            val result = editMutex.withLock { runSuspendCatching { editor.movePage(pageId, toPosition) } }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(pages = it) },
                    onFailure = { state.copy(message = DocumentDetailMessage.MovePageFailed) },
                )
            }
        }
    }

    /** Exports the document as `<title>.pdf` and queues it for sharing. */
    fun exportPdf(title: String, paperSize: PaperSize) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            val result = runSuspendCatching {
                val output = withContext(ioDispatcher) {
                    exportDir.mkdirs()
                    deleteOldExports()
                    File(exportDir, pdfFileName(title))
                }
                exporter.exportPdf(documentId, output, paperSize)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(isExporting = false, pendingShare = it) },
                    onFailure = { state.copy(isExporting = false, message = DocumentDetailMessage.ExportFailed) },
                )
            }
        }
    }

    fun shareHandled() {
        _uiState.update { it.copy(pendingShare = null) }
    }

    private fun deleteOldExports() {
        val cutoff = System.currentTimeMillis() - EXPORT_MAX_AGE_MS
        exportDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }
}

private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
