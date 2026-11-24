package app.nolva.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentTrash
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    val isLoading: Boolean = true,
    /** Trashed documents, most recently trashed first. */
    val documents: List<Document> = emptyList(),
    /** One-shot message; cleared with [TrashViewModel.messageShown]. */
    val message: TrashMessage? = null,
    /** Title of the document just restored, for its notice. */
    val restoredTitle: String? = null,
)

enum class TrashMessage { LoadFailed, RestoreFailed, DeleteFailed, EmptyFailed }

class TrashViewModel(
    private val trash: DocumentTrash,
    private val editor: DocumentEditor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val result = runSuspendCatching { trash.listTrash() }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(isLoading = false, documents = it) },
                    onFailure = { state.copy(isLoading = false, message = TrashMessage.LoadFailed) },
                )
            }
        }
    }

    fun restore(documentId: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { trash.restore(documentId) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { restored ->
                        state.copy(
                            documents = state.documents.filterNot { it.id == documentId },
                            restoredTitle = restored.title,
                        )
                    },
                    onFailure = { state.copy(message = TrashMessage.RestoreFailed) },
                )
            }
        }
    }

    /** Deletes one trashed document for good. */
    fun deleteForever(documentId: String) {
        viewModelScope.launch {
            val result = runSuspendCatching { editor.deleteDocument(documentId) }
            _uiState.update { state ->
                if (result.isSuccess || result.exceptionOrNull() is DocumentException.NotFound) {
                    state.copy(documents = state.documents.filterNot { it.id == documentId })
                } else {
                    state.copy(message = TrashMessage.DeleteFailed)
                }
            }
        }
    }

    /** Deletes every trashed document for good. */
    fun emptyTrash() {
        viewModelScope.launch {
            val result = runSuspendCatching { trash.emptyTrash() }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(documents = emptyList()) },
                    onFailure = { state.copy(message = TrashMessage.EmptyFailed) },
                )
            }
            // Partial failures leave some documents behind: show what's left.
            if (result.isFailure) refresh()
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null, restoredTitle = null) }
    }
}

/**
 * Whole days until [document] is deleted for good, counting a started day
 * as a full one (so it never says 0 while there's still time); 0 once due.
 */
internal fun daysLeft(document: Document, now: Instant): Int {
    val trashedAt = document.trashedAt ?: return DocumentTrash.RETENTION.toDays().toInt()
    val left = Duration.between(now, trashedAt.plus(DocumentTrash.RETENTION))
    if (left.isNegative || left.isZero) return 0
    val days = left.toDays()
    return (if (left.minusDays(days).isZero) days else days + 1).toInt()
}

/** Like [runCatching], but never swallows coroutine cancellation. */
private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
