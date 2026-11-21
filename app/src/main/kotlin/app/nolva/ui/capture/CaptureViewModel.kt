package app.nolva.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageCropper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CaptureUiState(
    /** Pages added to the document in this session, as stored (auto-cropped when possible). */
    val captured: List<Page> = emptyList(),
    /** Captures taken but not yet stored by the core. */
    val pendingSaves: Int = 0,
    val message: CaptureMessage? = null,
) {
    val isSaving: Boolean get() = pendingSaves > 0
    val capturedCount: Int get() = captured.size
}

enum class CaptureMessage { CaptureFailed, SaveFailed, DiscardFailed }

/** Minimum detection confidence to crop automatically after a capture. */
internal const val AUTO_CROP_MIN_CONFIDENCE = 0.6f

/**
 * Stores camera captures as pages of a document.
 *
 * The camera writes each photo to a temp file from [newCaptureFile]; this
 * ViewModel hands it to the core (which copies it into managed storage) and
 * always deletes the temp file afterwards.
 *
 * Each stored page is then cropped automatically when the core finds the
 * sheet with enough confidence. Auto-cropping is best effort: if it fails or
 * isn't confident, the page simply keeps its original photo and the user
 * can crop it by hand later.
 */
class CaptureViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
    private val cropper: PageCropper,
    private val editor: DocumentEditor,
    private val captureDir: File,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** A fresh temp file for the next capture. Call off the main thread. */
    fun newCaptureFile(): File {
        captureDir.mkdirs()
        return File(captureDir, "capture-${UUID.randomUUID()}.jpg")
    }

    fun onPictureTaken(file: File) {
        _uiState.update { it.copy(pendingSaves = it.pendingSaves + 1) }
        viewModelScope.launch {
            val saved: Page? = try {
                val page = repository.addPage(documentId, file)
                autoCrop(page.id) ?: page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                // Delete the temp file even if the screen is closed meanwhile.
                withContext(NonCancellable + ioDispatcher) { file.delete() }
            }
            _uiState.update { state ->
                state.copy(
                    pendingSaves = state.pendingSaves - 1,
                    captured = if (saved != null) state.captured + saved else state.captured,
                    message = if (saved != null) state.message else CaptureMessage.SaveFailed,
                )
            }
        }
    }

    /** @return the cropped page, or `null` if it was left as photographed. */
    private suspend fun autoCrop(pageId: String): Page? = try {
        val detection = cropper.detectCorners(pageId)
        if (detection != null && detection.confidence >= AUTO_CROP_MIN_CONFIDENCE) {
            cropper.cropPage(pageId, detection.corners)
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Keep the original photo; the page is already saved.
        null
    }

    /** Deletes a page captured in this session, e.g. a blurry shot to retake. */
    fun discard(pageId: String) {
        viewModelScope.launch {
            val failed = try {
                editor.deletePage(pageId)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentException.NotFound) {
                false // Already gone: nothing left to discard.
            } catch (e: Exception) {
                true
            }
            _uiState.update { state ->
                if (failed) {
                    state.copy(message = CaptureMessage.DiscardFailed)
                } else {
                    state.copy(captured = state.captured.filterNot { it.id == pageId })
                }
            }
        }
    }

    fun onCaptureFailed(file: File?) {
        file?.let { viewModelScope.launch(ioDispatcher) { it.delete() } }
        _uiState.update { it.copy(message = CaptureMessage.CaptureFailed) }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }
}
