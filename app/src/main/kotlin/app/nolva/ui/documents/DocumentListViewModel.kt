package app.nolva.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentListUiState(
    val isLoading: Boolean = true,
    val documents: List<Document> = emptyList(),
    val isCreating: Boolean = false,
    /** One-shot message to show to the user; cleared with [DocumentListViewModel.messageShown]. */
    val message: DocumentListMessage? = null,
)

enum class DocumentListMessage { LoadFailed, CreateFailed, InvalidTitle, RenameFailed, DeleteFailed }

class DocumentListViewModel(
    private val repository: DocumentRepository,
    private val editor: DocumentEditor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentListUiState())
    val uiState: StateFlow<DocumentListUiState> = _uiState.asStateFlow()

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

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }
}

/** Like [runCatching], but never swallows coroutine cancellation. */
private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
