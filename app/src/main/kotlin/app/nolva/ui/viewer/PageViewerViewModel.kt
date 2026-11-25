package app.nolva.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import app.nolva.data.PageRendering
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

/** Edits kept for undo; older ones are dropped. */
internal const val MAX_HISTORY = 30

data class PageViewerUiState(
    val isLoading: Boolean = true,
    val pages: List<Page> = emptyList(),
    val loadFailed: Boolean = false,
    /** Page being re-rendered (filter, rotation, undo); edits are disabled meanwhile. */
    val renderingPageId: String? = null,
    /** Progress of an edit touching several pages; edits are disabled meanwhile. */
    val bulkProgress: BulkProgress? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** One-shot error to show; cleared with [PageViewerViewModel.messageShown]. */
    val message: PageViewerMessage? = null,
    /** A page image ready for the share sheet; cleared with [PageViewerViewModel.shareHandled]. */
    val pendingShare: File? = null,
    /** Recognized text being shown; cleared with [PageViewerViewModel.textDismissed]. */
    val shownText: ShownText? = null,
) {
    /** Whether an edit is running; only one runs at a time. */
    val isBusy: Boolean get() = renderingPageId != null || bulkProgress != null
}

/** [done] of [total] pages rendered. */
data class BulkProgress(val done: Int, val total: Int)

/** A page's recognized text, as shown in the text sheet. Empty when the page has none. */
data class ShownText(val pageNumber: Int, val text: String)

enum class PageViewerMessage { FilterFailed, RotateFailed, UndoFailed, ShareFailed, TextPending, TextFailed }

/** One page going from [before] to [after]; an edit is one or more of these. */
private data class PageChange(val pageId: String, val before: PageRendering, val after: PageRendering)

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

    // Edit history of this viewer, newest last. Undoing restores each page's
    // whole rendering, which the core skips when it already matches, so a
    // failed undo can simply be retried.
    private val undoStack = ArrayDeque<List<PageChange>>()
    private val redoStack = ArrayDeque<List<PageChange>>()

    /**
     * Loads the pages. When coming back from the crop editor, a page whose
     * crop changed there becomes an undoable edit too. If pages were added,
     * removed or replaced elsewhere, the history no longer applies and is
     * dropped.
     */
    fun refresh() {
        viewModelScope.launch {
            val result = runSuspendCatching { repository.listPages(documentId) }
            result.onSuccess { pages ->
                val previous = _uiState.value
                if (!previous.isLoading) trackOutsideChanges(previous.pages, pages)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { pages ->
                        state.copy(isLoading = false, pages = pages, loadFailed = false).withHistory()
                    },
                    onFailure = { state.copy(isLoading = false, loadFailed = true) },
                )
            }
        }
    }

    private fun trackOutsideChanges(old: List<Page>, new: List<Page>) {
        if (old.map { it.id }.toSet() != new.map { it.id }.toSet()) {
            undoStack.clear()
            redoStack.clear()
            return
        }
        val before = old.associateBy { it.id }
        val changes = new.mapNotNull { page ->
            val previous = before.getValue(page.id).rendering
            if (previous == page.rendering) null else PageChange(page.id, previous, page.rendering)
        }
        if (changes.isNotEmpty()) record(changes)
    }

    /**
     * Renders [pageId] with [filter]. Takes up to a few seconds on large
     * photos, so only one edit runs at a time.
     */
    fun applyFilter(pageId: String, filter: PageFilter) {
        if (_uiState.value.pages.find { it.id == pageId }?.filter == filter) return
        editPage(pageId, PageViewerMessage.FilterFailed) { cropper.applyFilter(pageId, filter) }
    }

    /** Turns the page a quarter, keeping its crop and filter. */
    fun rotate(pageId: String, clockwise: Boolean) {
        editPage(pageId, PageViewerMessage.RotateFailed) { cropper.rotatePage(pageId, clockwise) }
    }

    /**
     * Applies [filter] to every page that doesn't have it yet, one by one,
     * as a single undoable edit. If a page fails, the pages already done
     * keep the filter (and can be undone) and the rest are left as they were.
     */
    fun applyFilterToAll(filter: PageFilter) {
        val state = _uiState.value
        if (state.isBusy) return
        val targets = state.pages.filter { it.filter != filter }
        if (targets.isEmpty()) return
        _uiState.update { it.copy(bulkProgress = BulkProgress(0, targets.size)) }
        viewModelScope.launch {
            val changes = mutableListOf<PageChange>()
            var failed = false
            for ((index, page) in targets.withIndex()) {
                val updated = runSuspendCatching { cropper.applyFilter(page.id, filter) }.getOrNull()
                if (updated == null) {
                    failed = true
                    break
                }
                changes += PageChange(page.id, page.rendering, updated.rendering)
                _uiState.update {
                    it.copy(pages = it.pages.replacing(updated), bulkProgress = BulkProgress(index + 1, targets.size))
                }
            }
            if (changes.isNotEmpty()) record(changes)
            _uiState.update {
                it.copy(
                    bulkProgress = null,
                    message = if (failed) PageViewerMessage.FilterFailed else it.message,
                ).withHistory()
            }
        }
    }

    fun undo() = travel(from = undoStack, to = redoStack) { it.before }

    fun redo() = travel(from = redoStack, to = undoStack) { it.after }

    /** Takes the newest edit off [from], restores each page to [target] and moves the edit to [to]. */
    private fun travel(
        from: ArrayDeque<List<PageChange>>,
        to: ArrayDeque<List<PageChange>>,
        target: (PageChange) -> PageRendering,
    ) {
        if (_uiState.value.isBusy) return
        val edit = from.removeLastOrNull() ?: return
        _uiState.update {
            it.copy(
                renderingPageId = edit.singleOrNull()?.pageId,
                bulkProgress = if (edit.size > 1) BulkProgress(0, edit.size) else null,
            ).withHistory()
        }
        viewModelScope.launch {
            var failed = false
            for ((index, change) in edit.withIndex()) {
                val updated = runSuspendCatching { cropper.setRendering(change.pageId, target(change)) }.getOrNull()
                if (updated == null) {
                    failed = true
                    break
                }
                _uiState.update { state ->
                    state.copy(
                        pages = state.pages.replacing(updated),
                        bulkProgress = state.bulkProgress?.copy(done = index + 1),
                    )
                }
            }
            // On failure the edit stays where it was, to be retried.
            if (failed) from.addLast(edit) else to.addLast(edit)
            _uiState.update {
                it.copy(
                    renderingPageId = null,
                    bulkProgress = null,
                    message = if (failed) PageViewerMessage.UndoFailed else it.message,
                ).withHistory()
            }
        }
    }

    private fun editPage(pageId: String, failure: PageViewerMessage, action: suspend () -> Page) {
        val state = _uiState.value
        if (state.isBusy) return
        val page = state.pages.find { it.id == pageId } ?: return
        _uiState.update { it.copy(renderingPageId = pageId) }
        viewModelScope.launch {
            val result = runSuspendCatching { action() }
            result.onSuccess { record(listOf(PageChange(pageId, page.rendering, it.rendering))) }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { current.copy(renderingPageId = null, pages = current.pages.replacing(it)) },
                    onFailure = { current.copy(renderingPageId = null, message = failure) },
                ).withHistory()
            }
        }
    }

    /** Adds a new edit; anything that was undone can no longer be redone. */
    private fun record(changes: List<PageChange>) {
        undoStack.addLast(changes)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun PageViewerUiState.withHistory() =
        copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())

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

private fun List<Page>.replacing(page: Page) = map { if (it.id == page.id) page else it }

/** Like [runCatching], but never swallows coroutine cancellation. */
private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
