package app.nolva.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.Document
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

enum class DocumentListMessage { LoadFailed, CreateFailed, InvalidTitle }

class DocumentListViewModel(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentListUiState())
    val uiState: StateFlow<DocumentListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
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
