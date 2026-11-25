package app.nolva.ui.annotate

import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.NormalizedPoint
import app.nolva.data.Page
import app.nolva.data.PageAnnotation
import app.nolva.data.PageAnnotator
import app.nolva.data.PageCropper
import app.nolva.data.Signature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AnnotationTool { Pen, Highlighter, Text, Signature, Eraser }

/** Pen widths, as fractions of the page's shorter side. */
enum class PenWidth(val value: Float) { Fine(0.003f), Medium(0.006f), Thick(0.012f) }

/** Ink colours (`0xRRGGBB`): near-black, blue, red, green. */
val PEN_COLORS = listOf(0x212121, 0x1A4D8F, 0xC62828, 0x2E7D32)

/** Marker colours: yellow, green, pink, blue. */
val HIGHLIGHT_COLORS = listOf(0xFFEB3B, 0x81C784, 0xF48FB1, 0x81D4FA)

/** Text annotations' size, as a fraction of the page's shorter side. */
internal const val TEXT_SIZE = 0.03f

/** Placed signatures' stroke width. */
private const val SIGNATURE_WIDTH = 0.004f

/** Undo steps kept while editing. */
internal const val MAX_EDITOR_HISTORY = 50

/** Smallest highlight, as a share of the image (smaller drags are taps). */
private const val MIN_HIGHLIGHT = 0.005f

data class AnnotationEditorUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val page: Page? = null,
    /** What the page shows now, bottom to top. */
    val annotations: List<PageAnnotation> = emptyList(),
    val tool: AnnotationTool = AnnotationTool.Pen,
    val penColor: Int = PEN_COLORS[1],
    val highlightColor: Int = HIGHLIGHT_COLORS[0],
    val penWidth: PenWidth = PenWidth.Medium,
    val signatures: List<Signature> = emptyList(),
    /** Placed with the next tap on the page. */
    val chosenSignature: Signature? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Changed since loaded: closing should ask first. */
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    /** Saved: the screen can close. */
    val isSaved: Boolean = false,
    val message: AnnotationEditorMessage? = null,
)

enum class AnnotationEditorMessage { SaveFailed, SignatureFailed, TooManySignatures, PlaceSignatureFirst }

class AnnotationEditorViewModel(
    private val pageId: String,
    private val cropper: PageCropper,
    private val annotator: PageAnnotator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnnotationEditorUiState())
    val uiState: StateFlow<AnnotationEditorUiState> = _uiState.asStateFlow()

    // Snapshots of the whole list, newest last: edits are small and few.
    private val undoStack = ArrayDeque<List<PageAnnotation>>()
    private val redoStack = ArrayDeque<List<PageAnnotation>>()
    private var savedAnnotations: List<PageAnnotation> = emptyList()

    init {
        viewModelScope.launch {
            val result = runSuspendCatching { cropper.getPage(pageId) to annotator.getAnnotations(pageId) }
            // Signatures are a convenience: the editor works without them.
            val signatures = runSuspendCatching { annotator.listSignatures() }.getOrDefault(emptyList())
            _uiState.update { state ->
                result.fold(
                    onSuccess = { (page, annotations) ->
                        savedAnnotations = annotations
                        state.copy(
                            isLoading = false,
                            page = page,
                            annotations = annotations,
                            signatures = signatures,
                        )
                    },
                    onFailure = { state.copy(isLoading = false, loadFailed = true) },
                )
            }
        }
    }

    fun selectTool(tool: AnnotationTool) {
        _uiState.update {
            it.copy(tool = tool, chosenSignature = if (tool == AnnotationTool.Signature) it.chosenSignature else null)
        }
    }

    /** Sets the colour of the current tool (markers have their own). */
    fun selectColor(color: Int) {
        _uiState.update {
            if (it.tool == AnnotationTool.Highlighter) it.copy(highlightColor = color) else it.copy(penColor = color)
        }
    }

    fun selectPenWidth(width: PenWidth) {
        _uiState.update { it.copy(penWidth = width) }
    }

    /** A finished pen stroke; a single point is a dot. */
    fun addStroke(points: List<NormalizedPoint>) {
        if (points.isEmpty()) return
        val state = _uiState.value
        // One annotation per stroke: undo and the eraser work stroke by stroke.
        edit(state.annotations + PageAnnotation.Ink(listOf(points), state.penColor, state.penWidth.value))
    }

    /** A marker dragged from [from] to [to], in either direction. */
    fun addHighlight(from: NormalizedPoint, to: NormalizedPoint) {
        val left = minOf(from.x, to.x)
        val right = maxOf(from.x, to.x)
        val top = minOf(from.y, to.y)
        val bottom = maxOf(from.y, to.y)
        if (right - left < MIN_HIGHLIGHT || bottom - top < MIN_HIGHLIGHT) return
        val state = _uiState.value
        edit(state.annotations + PageAnnotation.Highlight(left, top, right, bottom, state.highlightColor))
    }

    /** Text typed for a tap at [at] (its top left). */
    fun addText(text: String, at: NormalizedPoint) {
        if (text.isBlank()) return
        val state = _uiState.value
        edit(state.annotations + PageAnnotation.Text(text.trimEnd(), at.x, at.y, TEXT_SIZE, state.penColor))
    }

    /** Removes the topmost annotation under [at]; [tolerance] in image pixels. */
    fun eraseAt(at: NormalizedPoint, image: Size, tolerance: Float) {
        val annotations = _uiState.value.annotations
        val index = hitTest(annotations, at, image, tolerance) ?: return
        edit(annotations.filterIndexed { i, _ -> i != index })
    }

    fun chooseSignature(signature: Signature) {
        _uiState.update { it.copy(tool = AnnotationTool.Signature, chosenSignature = signature) }
    }

    /** Places the chosen signature centred on [at] of an image of [image] size. */
    fun placeSignature(at: NormalizedPoint, image: Size) {
        val state = _uiState.value
        val signature = state.chosenSignature
        if (signature == null) {
            _uiState.update { it.copy(message = AnnotationEditorMessage.PlaceSignatureFirst) }
            return
        }
        edit(state.annotations + placeSignature(signature, at, image, state.penColor, SIGNATURE_WIDTH))
    }

    /** Saves a signature drawn on the pad and chooses it for placing. */
    fun saveSignature(signature: NormalizedSignature) {
        viewModelScope.launch {
            val result = runSuspendCatching { annotator.saveSignature(signature.strokes, signature.aspectRatio) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { saved ->
                        state.copy(
                            signatures = state.signatures + saved,
                            chosenSignature = saved,
                            tool = AnnotationTool.Signature,
                        )
                    },
                    onFailure = {
                        val full = state.signatures.size >= MAX_SAVED_SIGNATURES
                        state.copy(
                            message = if (full) {
                                AnnotationEditorMessage.TooManySignatures
                            } else {
                                AnnotationEditorMessage.SignatureFailed
                            },
                        )
                    },
                )
            }
        }
    }

    /** Forgets a saved signature; pages it was placed on keep it. */
    fun deleteSignature(signature: Signature) {
        viewModelScope.launch {
            val result = runSuspendCatching { annotator.deleteSignature(signature.id) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            signatures = state.signatures - signature,
                            chosenSignature = state.chosenSignature.takeUnless { it == signature },
                        )
                    },
                    onFailure = { state.copy(message = AnnotationEditorMessage.SignatureFailed) },
                )
            }
        }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_uiState.value.annotations)
        show(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_uiState.value.annotations)
        show(next)
    }

    /** Saves every change at once; the screen closes when [AnnotationEditorUiState.isSaved]. */
    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.page == null) return
        if (!state.isDirty) {
            _uiState.update { it.copy(isSaved = true) }
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val annotations = state.annotations
            val result = runSuspendCatching { annotator.setAnnotations(pageId, annotations) }
            _uiState.update { current ->
                result.fold(
                    onSuccess = {
                        savedAnnotations = annotations
                        current.copy(isSaving = false, isSaved = true, isDirty = current.annotations != annotations)
                    },
                    onFailure = { current.copy(isSaving = false, message = AnnotationEditorMessage.SaveFailed) },
                )
            }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun edit(annotations: List<PageAnnotation>) {
        undoStack.addLast(_uiState.value.annotations)
        while (undoStack.size > MAX_EDITOR_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        show(annotations)
    }

    private fun show(annotations: List<PageAnnotation>) {
        _uiState.update {
            it.copy(
                annotations = annotations,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                isDirty = annotations != savedAnnotations,
            )
        }
    }

    private companion object {
        /** The core keeps at most this many. */
        const val MAX_SAVED_SIGNATURES = 10
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
