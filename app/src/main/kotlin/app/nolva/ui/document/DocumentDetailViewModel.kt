package app.nolva.ui.document

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageImporter
import app.nolva.data.PaperSize
import app.nolva.data.DOCX_MIME_TYPE
import app.nolva.data.DocxExporter
import app.nolva.data.ExportPreferences
import app.nolva.data.PdfExporter
import app.nolva.data.PdfSettings
import app.nolva.data.TextRecognitionRunner
import app.nolva.data.docxFileName
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
    val pendingShare: ExportedFile? = null,
    /** Pages the last Word export had no text for; shown with [DocumentDetailMessage.DocxMissingText]. */
    val pagesMissingText: Int = 0,
    /** Whether PDFs are exported with page numbers; remembered between documents. */
    val pageNumbers: Boolean = false,
) {
    val isImporting: Boolean get() = pendingImports > 0
}

/** An exported file and its MIME type, for the share sheet. */
data class ExportedFile(val file: File, val mimeType: String)

enum class DocumentDetailMessage {
    LoadFailed,
    ImportFailed,
    UnsupportedImage,
    ExportFailed,
    DocxMissingText,
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
    private val docxExporter: DocxExporter,
    private val textRecognition: TextRecognitionRunner,
    private val exportPreferences: ExportPreferences,
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

    init {
        viewModelScope.launch {
            // Unreadable preferences just mean the default: no numbers.
            val enabled = runSuspendCatching { exportPreferences.pageNumbers() }.getOrDefault(false)
            _uiState.update { it.copy(pageNumbers = enabled) }
        }
    }

    /** Turns page numbers in exported PDFs on or off, for this and later documents. */
    fun setPageNumbers(enabled: Boolean) {
        _uiState.update { it.copy(pageNumbers = enabled) }
        viewModelScope.launch { runSuspendCatching { exportPreferences.setPageNumbers(enabled) } }
    }

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
                exporter.exportPdf(documentId, output, PdfSettings(paperSize, _uiState.value.pageNumbers))
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(isExporting = false, pendingShare = ExportedFile(it.file, "application/pdf"))
                    },
                    onFailure = { state.copy(isExporting = false, message = DocumentDetailMessage.ExportFailed) },
                )
            }
        }
    }

    /**
     * Exports the document as `<title>.docx` and queues it for sharing.
     * Pages whose text isn't recognized yet are recognized first, so the
     * document isn't missing pages that the background job hasn't reached.
     */
    fun exportDocx(title: String, includeImages: Boolean) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            // If recognition fails (e.g. ML Kit), export anyway: the pages
            // come out empty and the user is told how many.
            runSuspendCatching { textRecognition.runForDocument(documentId) }
            val result = runSuspendCatching {
                val output = withContext(ioDispatcher) {
                    exportDir.mkdirs()
                    deleteOldExports()
                    File(exportDir, docxFileName(title))
                }
                docxExporter.exportDocx(documentId, output, includeImages)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { export ->
                        state.copy(
                            isExporting = false,
                            pendingShare = ExportedFile(export.file, DOCX_MIME_TYPE),
                            pagesMissingText = export.pagesPendingText,
                            message = if (export.pagesPendingText > 0) {
                                DocumentDetailMessage.DocxMissingText
                            } else {
                                state.message
                            },
                        )
                    },
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
