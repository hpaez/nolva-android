package app.nolva.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentRepository
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
    /** Pages successfully added to the document in this session. */
    val capturedCount: Int = 0,
    /** Captures taken but not yet stored by the core. */
    val pendingSaves: Int = 0,
    val message: CaptureMessage? = null,
) {
    val isSaving: Boolean get() = pendingSaves > 0
}

enum class CaptureMessage { CaptureFailed, SaveFailed }

/**
 * Stores camera captures as pages of a document.
 *
 * The camera writes each photo to a temp file from [newCaptureFile]; this
 * ViewModel hands it to the core (which copies it into managed storage) and
 * always deletes the temp file afterwards.
 */
class CaptureViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
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
            val saved = try {
                repository.addPage(documentId, file)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                // Delete the temp file even if the screen is closed meanwhile.
                withContext(NonCancellable + ioDispatcher) { file.delete() }
            }
            _uiState.update { state ->
                state.copy(
                    pendingSaves = state.pendingSaves - 1,
                    capturedCount = if (saved) state.capturedCount + 1 else state.capturedCount,
                    message = if (saved) state.message else CaptureMessage.SaveFailed,
                )
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
