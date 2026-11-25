package app.nolva.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.DocumentTrash
import app.nolva.data.Folder
import app.nolva.data.FolderStore
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
    /** Folder this screen shows; `null` for the top level. */
    val folderId: String? = null,
    val isLoading: Boolean = true,
    /** Every document, in every folder; the screen shows [visibleDocuments]. */
    val documents: List<Document> = emptyList(),
    /** Every folder, by name; the top level shows [visibleFolders]. */
    val folders: List<Folder> = emptyList(),
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
    /**
     * The document just moved to the trash, while its "Undo" notice shows;
     * cleared with [DocumentListViewModel.undoTrash] or [DocumentListViewModel.trashNoticeShown].
     */
    val trashedDocument: Document? = null,
    /** One-shot message to show to the user; cleared with [DocumentListViewModel.messageShown]. */
    val message: DocumentListMessage? = null,
) {
    /**
     * Documents whose title contains [query] (ignoring case and accents) or
     * whose pages' text matches it, most recent first.
     */
    val visibleDocuments: List<Document> by lazy {
        val needle = query.searchKey()
        // A search from the top level spans every folder; inside a folder it stays there.
        val scope = if (folderId == null && needle.isNotEmpty()) {
            documents
        } else {
            documents.filter { it.folderId == folderId }
        }
        if (needle.isEmpty()) {
            scope
        } else {
            scope.filter { needle in it.title.searchKey() || it.id in contentMatches }
        }
    }

    /** Folders listed above the documents: only at the top level, filtered by name while searching. */
    val visibleFolders: List<Folder> by lazy {
        val needle = query.searchKey()
        when {
            folderId != null -> emptyList()
            needle.isEmpty() -> folders
            else -> folders.filter { needle in it.name.searchKey() }
        }
    }

    /** Nothing at all to show here, search aside: no documents, and at the top level no folders. */
    val isEmpty: Boolean
        get() = documents.none { it.folderId == folderId } && (folderId != null || folders.isEmpty())
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
    RestoreFailed,
    DuplicateFailed,
    FolderFailed,
    MoveFailed,
}

class DocumentListViewModel(
    /** Folder to show; `null` for the top level. */
    private val folderId: String?,
    private val repository: DocumentRepository,
    private val editor: DocumentEditor,
    private val textStore: PageTextStore,
    private val pdfImporter: PdfImporter,
    private val trash: DocumentTrash,
    private val folderStore: FolderStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentListUiState(folderId = folderId))
    val uiState: StateFlow<DocumentListUiState> = _uiState.asStateFlow()

    private var contentSearch: Job? = null

    fun refresh() {
        // The spinner only shows on the first load (initial state); later
        // refreshes keep the current list visible while reloading.
        viewModelScope.launch {
            val result = runSuspendCatching { repository.listDocuments() to folderStore.listFolders() }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { (documents, folders) ->
                        state.copy(isLoading = false, documents = documents, folders = folders)
                    },
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
            val result = runSuspendCatching {
                if (folderId == null) repository.createDocument(title) else folderStore.createDocumentIn(title, folderId)
            }
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

    /**
     * Creates a folder; with [thenMove], also moves that document into it
     * (the "New folder…" option of the move dialog).
     */
    fun createFolder(name: String, thenMove: String? = null) {
        viewModelScope.launch {
            val result = runSuspendCatching {
                val folder = folderStore.createFolder(name)
                if (thenMove != null) folderStore.moveDocuments(listOf(thenMove), folder.id)
                folder
            }
            result.fold(
                onSuccess = { folder ->
                    _uiState.update { state ->
                        state.copy(
                            folders = (state.folders + folder).sortedBy { it.name.searchKey() },
                            documents = state.documents.map {
                                if (it.id == thenMove) it.copy(folderId = folder.id) else it
                            },
                        )
                    }
                    if (thenMove != null) reloadFolders()
                },
                onFailure = { error -> _uiState.update { it.copy(message = folderError(error)) } },
            )
        }
    }

    fun renameFolder(folderId: String, name: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { folderStore.renameFolder(folderId, name) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { renamed ->
                        state.copy(
                            folders = state.folders.map { if (it.id == folderId) renamed else it }
                                .sortedBy { it.name.searchKey() },
                        )
                    },
                    onFailure = { error -> state.copy(message = folderError(error)) },
                )
            }
        }
    }

    /** Deletes a folder; its documents move to the top level. */
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { folderStore.deleteFolder(folderId) }
            _uiState.update { state ->
                if (result.isSuccess || result.exceptionOrNull() is DocumentException.NotFound) {
                    state.copy(
                        folders = state.folders.filterNot { it.id == folderId },
                        documents = state.documents.map {
                            if (it.folderId == folderId) it.copy(folderId = null) else it
                        },
                    )
                } else {
                    state.copy(message = DocumentListMessage.FolderFailed)
                }
            }
        }
    }

    /** Moves a document into [toFolderId] (`null`: the top level). */
    fun moveDocument(documentId: String, toFolderId: String?) {
        viewModelScope.launch {
            val result = runSuspendCatching { folderStore.moveDocuments(listOf(documentId), toFolderId) }
            if (result.isSuccess) {
                _uiState.update { state ->
                    state.copy(
                        documents = state.documents.map {
                            if (it.id == documentId) it.copy(folderId = toFolderId) else it
                        },
                    )
                }
                reloadFolders()
            } else {
                _uiState.update { it.copy(message = DocumentListMessage.MoveFailed) }
            }
        }
    }

    /** Refreshes the folders' document counts after moves. */
    private suspend fun reloadFolders() {
        runSuspendCatching { folderStore.listFolders() }.getOrNull()?.let { folders ->
            _uiState.update { it.copy(folders = folders) }
        }
    }

    private fun folderError(error: Throwable) =
        if (error is DocumentException.InvalidInput) DocumentListMessage.InvalidTitle else DocumentListMessage.FolderFailed

    /** Moves a document to the trash; [undoTrash] brings it back. */
    fun deleteDocument(documentId: String) {
        val document = _uiState.value.documents.find { it.id == documentId } ?: return
        viewModelScope.launch {
            val result = runSuspendCatching { trash.moveToTrash(documentId) }
            _uiState.update { state ->
                // Already gone (e.g. deleted twice) counts as done.
                if (result.isSuccess || result.exceptionOrNull() is DocumentException.NotFound) {
                    state.copy(
                        documents = state.documents.filterNot { it.id == documentId },
                        trashedDocument = document.takeIf { result.isSuccess },
                    )
                } else {
                    state.copy(message = DocumentListMessage.DeleteFailed)
                }
            }
        }
    }

    /** Restores the document from the last "moved to trash" notice. */
    fun undoTrash() {
        val document = _uiState.value.trashedDocument ?: return
        _uiState.update { it.copy(trashedDocument = null) }
        viewModelScope.launch {
            val result = runSuspendCatching { trash.restore(document.id) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { restored ->
                        // Back in its place: the list is ordered by last update.
                        val documents = (state.documents.filterNot { it.id == restored.id } + restored)
                            .sortedByDescending { it.updatedAt }
                        state.copy(documents = documents)
                    },
                    onFailure = { state.copy(message = DocumentListMessage.RestoreFailed) },
                )
            }
        }
    }

    fun trashNoticeShown() {
        _uiState.update { it.copy(trashedDocument = null) }
    }

    /** Copies a document as [title]; the copy goes to the top of the list. */
    fun duplicateDocument(documentId: String, title: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { editor.duplicateDocument(documentId, title) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { copy -> state.copy(documents = listOf(copy) + state.documents) },
                    onFailure = { state.copy(message = DocumentListMessage.DuplicateFailed) },
                )
            }
        }
    }

    /** Imports a PDF as a new document, then opens it. One import at a time. */
    fun importPdf(uri: String) {
        if (_uiState.value.pdfImport != null) return
        _uiState.update { it.copy(pdfImport = PdfImportProgress(0, 0)) }
        viewModelScope.launch {
            val result = runSuspendCatching {
                val document = pdfImporter.import(uri) { progress -> _uiState.update { it.copy(pdfImport = progress) } }
                // Imported from inside a folder: it belongs there.
                if (folderId != null) {
                    folderStore.moveDocuments(listOf(document.id), folderId)
                    document.copy(folderId = folderId)
                } else {
                    document
                }
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
