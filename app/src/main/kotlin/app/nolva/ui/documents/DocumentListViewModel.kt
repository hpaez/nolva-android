package app.nolva.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.PageTextStore
import app.nolva.data.PdfImportProgress
import app.nolva.data.PdfImporter
import app.nolva.data.TextMatch
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentListUiState(
    val isLoading: Boolean = true,
    val documents: List<Document> = emptyList(),
    /** Text typed in the search field; empty shows every document. */
    val query: String = "",
    /**
     * Best page match per document id for [query] in the pages' recognized
     * text. Lags slightly behind [query] while typing.
     */
    val contentMatches: Map<String, TextMatch> = emptyMap(),
    val isCreating: Boolean = false,
    /** Progress of the PDF being imported; `null` when none is. */
    val pdfImport: PdfImportProgress? = null,
    /** A document to open right away (an imported PDF); cleared with [DocumentListViewModel.documentOpened]. */
    val openDocument: Document? = null,
    /** One-shot message to show to the user; cleared with [DocumentListViewModel.messageShown]. */
    val message: DocumentListMessage? = null,
) {
    /**
     * Documents whose title contains [query] (ignoring case and accents) or
     * whose pages' text matches it, most recent first.
     */
    val visibleDocuments: List<Document> by lazy {
        val needle = query.searchKey()
        if (needle.isEmpty()) {
            documents
        } else {
            documents.filter { needle in it.title.searchKey() || it.id in contentMatches }
        }
    }
}

private val diacritics = Regex("\\p{Mn}+")

/** Lowercase, trimmed and without accents, so "factura" finds "Factura Única". */
internal fun String.searchKey(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFD).replace(diacritics, "").lowercase(Locale.ROOT)

enum class DocumentListMessage {
    LoadFailed,
    CreateFailed,
    InvalidTitle,
    RenameFailed,
    DeleteFailed,
    PdfUnreadable,
    PdfTooLarge,
    PdfImportFailed,
}

class DocumentListViewModel(
    private val repository: DocumentRepository,
    private val editor: DocumentEditor,
    private val textStore: PageTextStore,
    private val pdfImporter: PdfImporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentListUiState())
    val uiState: StateFlow<DocumentListUiState> = _uiState.asStateFlow()

    private var contentSearch: Job? = null

    fun refresh() {
        // The spinner only shows on the first load (initial state); later
        // refreshes keep the current list visible while reloading.
        viewModelScope.launch {
            val result = runSuspendCatching { repository.listDocuments() }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(isLoading = false, documents = it) },
                    onFailure = { state.copy(isLoading = false, message = DocumentListMessage.LoadFailed) },
                )
            }
            // Pages may have been recognized since the last search.
            searchContent(_uiState.value.query, debounce = false)
        }
    }

    fun createDocument(title: String) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true) }
            val result = runSuspendCatching { repository.createDocument(title) }
            result.fold(
                onSuccess = { created ->
                    // Newest first, matching the repository's ordering.
                    _uiState.update { it.copy(isCreating = false, documents = listOf(created) + it.documents) }
                },
                onFailure = { error ->
                    val message = if (error is DocumentException.InvalidInput) {
                        DocumentListMessage.InvalidTitle
                    } else {
                        DocumentListMessage.CreateFailed
                    }
                    _uiState.update { it.copy(isCreating = false, message = message) }
                },
            )
        }
    }

    fun renameDocument(documentId: String, title: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { editor.renameDocument(documentId, title) }
            result.fold(
                onSuccess = { renamed ->
                    // Renaming touches the document, so it becomes the most recent.
                    _uiState.update { state ->
                        state.copy(documents = listOf(renamed) + state.documents.filterNot { it.id == renamed.id })
                    }
                },
                onFailure = { error ->
                    val message = if (error is DocumentException.InvalidInput) {
                        DocumentListMessage.InvalidTitle
                    } else {
                        DocumentListMessage.RenameFailed
                    }
                    _uiState.update { it.copy(message = message) }
                },
            )
        }
    }

    /** Permanently deletes a document and its pages. */
    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { editor.deleteDocument(documentId) }
            _uiState.update { state ->
                // Already gone (e.g. deleted twice) counts as done.
                if (result.isSuccess || result.exceptionOrNull() is DocumentException.NotFound) {
                    state.copy(documents = state.documents.filterNot { it.id == documentId })
                } else {
                    state.copy(message = DocumentListMessage.DeleteFailed)
                }
            }
        }
    }

    /** Imports a PDF as a new document, then opens it. One import at a time. */
    fun importPdf(uri: String) {
        if (_uiState.value.pdfImport != null) return
        _uiState.update { it.copy(pdfImport = PdfImportProgress(0, 0)) }
        viewModelScope.launch {
            val result = runSuspendCatching {
                pdfImporter.import(uri) { progress -> _uiState.update { it.copy(pdfImport = progress) } }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { document ->
                        state.copy(
                            pdfImport = null,
                            documents = listOf(document) + state.documents.filterNot { it.id == document.id },
                            openDocument = document,
                        )
                    },
                    onFailure = { error ->
                        val message = when (error) {
                            is DocumentException.Unsupported -> DocumentListMessage.PdfUnreadable
                            is DocumentException.InvalidInput -> DocumentListMessage.PdfTooLarge
                            else -> DocumentListMessage.PdfImportFailed
                        }
                        state.copy(pdfImport = null, message = message)
                    },
                )
            }
        }
    }

    fun documentOpened() {
        _uiState.update { it.copy(openDocument = null) }
    }

    fun search(query: String) {
        _uiState.update { it.copy(query = query) }
        searchContent(query, debounce = true)
    }

    /**
     * Looks [query] up in the pages' text. While typing, waits for a short
     * pause first, and a newer query cancels the previous search. Failures
     * are silent: title matches still show.
     */
    private fun searchContent(query: String, debounce: Boolean) {
        contentSearch?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(contentMatches = emptyMap()) }
            return
        }
        contentSearch = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val matches = runSuspendCatching { textStore.searchText(query) }.getOrNull() ?: return@launch
            _uiState.update { state ->
                if (state.query != query) {
                    state
                } else {
                    // Results come best first: keep each document's best page.
                    state.copy(contentMatches = matches.reversed().associateBy { it.documentId })
                }
            }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }
}

/** Pause in typing before searching the pages' text. */
internal const val SEARCH_DEBOUNCE_MS = 250L

/** Like [runCatching], but never swallows coroutine cancellation. */
private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
