package app.nolva.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import app.nolva.data.PageTextStore
import app.nolva.data.pageImageFileName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PageViewerUiState(
    val isLoading: Boolean = true,
    val pages: List<Page> = emptyList(),
    val loadFailed: Boolean = false,
    /** Page whose filter is being rendered; filters are disabled meanwhile. */
    val filteringPageId: String? = null,
    /** One-shot error to show; cleared with [PageViewerViewModel.messageShown]. */
    val message: PageViewerMessage? = null,
    /** A page image ready for the share sheet; cleared with [PageViewerViewModel.shareHandled]. */
    val pendingShare: File? = null,
    /** Recognized text being shown; cleared with [PageViewerViewModel.textDismissed]. */
    val shownText: ShownText? = null,
)

/** A page's recognized text, as shown in the text sheet. Empty when the page has none. */
data class ShownText(val pageNumber: Int, val text: String)

enum class PageViewerMessage { FilterFailed, ShareFailed, TextPending, TextFailed }

class PageViewerViewModel(
    private val documentId: String,
    private val title: String,
    private val repository: DocumentRepository,
    private val cropper: PageCropper,
    private val textStore: PageTextStore,
    /** Shareable folder exposed by the FileProvider. */
    private val exportDir: File,
    private val ioDispatcher: CoroutineDispatcher,
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
                    onFailure = { current.copy(filteringPageId = null, message = PageViewerMessage.FilterFailed) },
                )
            }
        }
    }

    /**
     * Copies the page's image (cropped and filtered, if it is) to the export
     * folder as `<title> - <number>.jpg` and queues it for sharing. Page
     * files themselves are never exposed to other apps.
     */
    fun sharePage(pageId: String) {
        val page = _uiState.value.pages.find { it.id == pageId } ?: return
        val number = _uiState.value.pages.indexOf(page) + 1
        viewModelScope.launch {
            val result = runSuspendCatching {
                withContext(ioDispatcher) {
                    exportDir.mkdirs()
                    page.displayFile.copyTo(File(exportDir, pageImageFileName(title, number)), overwrite = true)
                }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(pendingShare = it) },
                    onFailure = { state.copy(message = PageViewerMessage.ShareFailed) },
                )
            }
        }
    }

    /** Shows the page's recognized text, or says it's still being recognized. */
    fun showText(pageId: String) {
        val pages = _uiState.value.pages
        val number = pages.indexOfFirst { it.id == pageId } + 1
        if (number == 0) return
        viewModelScope.launch {
            val result = runSuspendCatching { textStore.getPageText(pageId) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { text ->
                        if (text == null) {
                            state.copy(message = PageViewerMessage.TextPending)
                        } else {
                            state.copy(shownText = ShownText(number, text.text))
                        }
                    },
                    onFailure = { state.copy(message = PageViewerMessage.TextFailed) },
                )
            }
        }
    }

    fun textDismissed() {
        _uiState.update { it.copy(shownText = null) }
    }

    fun shareHandled() {
        _uiState.update { it.copy(pendingShare = null) }
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
