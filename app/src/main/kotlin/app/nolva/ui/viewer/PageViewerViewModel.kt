package app.nolva.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PageViewerUiState(
    val isLoading: Boolean = true,
    val pages: List<Page> = emptyList(),
    val loadFailed: Boolean = false,
    /** Page whose filter is being rendered; filters are disabled meanwhile. */
    val filteringPageId: String? = null,
    /** One-shot error to show; cleared with [PageViewerViewModel.messageShown]. */
    val filterFailed: Boolean = false,
)

class PageViewerViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
    private val cropper: PageCropper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PageViewerUiState())
    val uiState: StateFlow<PageViewerUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val result = runSuspendCatching { repository.listPages(documentId) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(isLoading = false, pages = it, loadFailed = false) },
                    onFailure = { state.copy(isLoading = false, loadFailed = true) },
                )
            }
        }
    }

    /**
     * Renders [pageId] with [filter]. Takes up to a few seconds on large
     * photos, so only one page is filtered at a time.
     */
    fun applyFilter(pageId: String, filter: PageFilter) {
        val state = _uiState.value
        if (state.filteringPageId != null) return
        if (state.pages.find { it.id == pageId }?.filter == filter) return
        _uiState.update { it.copy(filteringPageId = pageId) }
        viewModelScope.launch {
            val result = runSuspendCatching { cropper.applyFilter(pageId, filter) }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { page ->
                        current.copy(
                            filteringPageId = null,
                            pages = current.pages.map { if (it.id == page.id) page else it },
                        )
                    },
                    onFailure = { current.copy(filteringPageId = null, filterFailed = true) },
                )
            }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(filterFailed = false) }
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
