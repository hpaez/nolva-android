package app.nolva.ui.crop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.Corners
import app.nolva.data.DocumentException
import app.nolva.data.NormalizedPoint
import app.nolva.data.Page
import app.nolva.data.PageCropper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Below this detection confidence the editor asks the user to check the corners. */
internal const val CHECK_CORNERS_BELOW = 0.6f

enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

data class CropUiState(
    val isLoading: Boolean = true,
    val page: Page? = null,
    val corners: Corners = Corners.FULL,
    /** The starting corners are a guess; prompt the user to check them. */
    val shouldCheckCorners: Boolean = false,
    val isSaving: Boolean = false,
    /** Set once the crop is stored; the screen then closes. */
    val saved: Boolean = false,
    val message: CropMessage? = null,
)

enum class CropMessage { LoadFailed, InvalidCorners, SaveFailed }

class CropViewModel(
    private val pageId: String,
    private val cropper: PageCropper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CropUiState())
    val uiState: StateFlow<CropUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val page = cropper.getPage(pageId)
                // Resume a previous crop; otherwise start from detection.
                val (corners, check) = page.corners?.let { it to false } ?: run {
                    val detection = cropper.detectCorners(pageId)
                    (detection?.corners ?: Corners.FULL) to
                        (detection == null || detection.confidence < CHECK_CORNERS_BELOW)
                }
                _uiState.value = CropUiState(isLoading = false, page = page, corners = corners, shouldCheckCorners = check)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = CropUiState(isLoading = false, message = CropMessage.LoadFailed)
            }
        }
    }

    /** Moves one corner, clamped to the image. */
    fun moveCorner(corner: Corner, to: NormalizedPoint) {
        val point = NormalizedPoint(to.x.coerceIn(0f, 1f), to.y.coerceIn(0f, 1f))
        _uiState.update { state ->
            val c = state.corners
            state.copy(
                corners = when (corner) {
                    Corner.TopLeft -> c.copy(topLeft = point)
                    Corner.TopRight -> c.copy(topRight = point)
                    Corner.BottomRight -> c.copy(bottomRight = point)
                    Corner.BottomLeft -> c.copy(bottomLeft = point)
                },
            )
        }
    }

    fun selectFullImage() {
        _uiState.update { it.copy(corners = Corners.FULL) }
    }

    fun apply() {
        val state = _uiState.value
        if (state.isSaving || state.page == null) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                cropper.cropPage(pageId, state.corners)
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is DocumentException.InvalidInput) CropMessage.InvalidCorners else CropMessage.SaveFailed
                _uiState.update { it.copy(isSaving = false, message = message) }
            }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }
}

internal fun Corners.get(corner: Corner): NormalizedPoint = when (corner) {
    Corner.TopLeft -> topLeft
    Corner.TopRight -> topRight
    Corner.BottomRight -> bottomRight
    Corner.BottomLeft -> bottomLeft
}
