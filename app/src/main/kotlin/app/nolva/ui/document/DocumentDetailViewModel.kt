package app.nolva.ui.document

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentDetailUiState(
    val isLoading: Boolean = true,
    val pages: List<Page> = emptyList(),
    /** Number of images still being imported. */
    val pendingImports: Int = 0,
    val message: DocumentDetailMessage? = null,
) {
    val isImporting: Boolean get() = pendingImports > 0
}

enum class DocumentDetailMessage { LoadFailed, ImportFailed, UnsupportedImage }

class DocumentDetailViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
    private val importer: PageImporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    init {
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
