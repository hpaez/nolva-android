package app.nolva.ui.annotate

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.NormalizedPoint
import app.nolva.data.Signature

/** Touch reach of the eraser, on screen. */
private val ERASER_REACH = 16.dp

/** Pen points closer than this on screen are skipped: smaller, smoother strokes. */
private val MIN_POINT_STEP = 2.dp

@Composable
fun AnnotationEditorRoute(
    viewModel: AnnotationEditorViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.isSaved) { if (uiState.isSaved) onDone() }
    var confirmingDiscard by remember { mutableStateOf(false) }
    var drawingSignature by remember { mutableStateOf(false) }
    var textAt by remember { mutableStateOf<NormalizedPoint?>(null) }
    val close = { if (uiState.isDirty) confirmingDiscard = true else onDone() }
    BackHandler(onBack = close)

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = uiState.message?.let { stringResource(it.textRes()) }
    LaunchedEffect(uiState.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                canSave = uiState.page != null && !uiState.isSaving,
                onClose = close,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSave = viewModel::save,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val page = uiState.page
                when {
                    uiState.isLoading -> CircularProgressIndicator(color = Color.White)
                    page == null -> Text(
                        stringResource(R.string.error_load_annotations),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    else -> DrawingSurface(
                        uiState = uiState,
                        viewModel = viewModel,
                        image = page.displayFile,
                        onAskText = { textAt = it },
                    )
                }
                if (uiState.isSaving) CircularProgressIndicator(color = Color.White)
            }
            ToolPanel(
                uiState = uiState,
                onTool = viewModel::selectTool,
                onColor = viewModel::selectColor,
                onWidth = viewModel::selectPenWidth,
                onChooseSignature = viewModel::chooseSignature,
                onDeleteSignature = viewModel::deleteSignature,
                onNewSignature = { drawingSignature = true },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 140.dp),
        )
    }

    textAt?.let { at ->
        TextDialog(
            onDismiss = { textAt = null },
            onAdd = { text ->
                textAt = null
                viewModel.addText(text, at)
            },
        )
    }
    if (drawingSignature) {
        SignaturePadDialog(
            onDismiss = { drawingSignature = false },
            onSave = { signature ->
                drawingSignature = false
                viewModel.saveSignature(signature)
            },
        )
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.discard_annotations_title)) },
            text = { Text(stringResource(R.string.discard_annotations_body)) },
            confirmButton = {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }
        Text(
            stringResource(R.string.annotate_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        WhiteIcon(R.drawable.ic_undo, R.string.undo, canUndo, onUndo)
        WhiteIcon(R.drawable.ic_redo, R.string.redo, canRedo, onRedo)
        TextButton(onClick = onSave, enabled = canSave) {
            Text(stringResource(R.string.done), color = if (canSave) Color.White else Color.White.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun WhiteIcon(icon: Int, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(label),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        )
    }
}

/** The page, its annotations and the gestures of the current tool. */
@Composable
private fun DrawingSurface(
    uiState: AnnotationEditorUiState,
    viewModel: AnnotationEditorViewModel,
    image: java.io.File,
    onAskText: (NormalizedPoint) -> Unit,
) {
    var imageRect by remember { mutableStateOf<Rect?>(null) }
    var imageSize by remember { mutableStateOf<Size?>(null) }
    // The stroke or marker being drawn, in screen pixels.
    val current = remember { mutableStateListOf<Offset>() }
    val density = LocalDensity.current
    val minStep = with(density) { MIN_POINT_STEP.toPx() }
    val eraserReach = with(density) { ERASER_REACH.toPx() }
    val state by rememberUpdatedState(uiState)

    fun normalized(p: Offset, rect: Rect) = NormalizedPoint(
        ((p.x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((p.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )

    fun tap(p: Offset) {
        val rect = imageRect ?: return
        val size = imageSize ?: return
        val at = normalized(p, rect)
        when (state.tool) {
            AnnotationTool.Pen -> viewModel.addStroke(listOf(at))
            AnnotationTool.Highlighter -> Unit
            AnnotationTool.Text -> onAskText(at)
            AnnotationTool.Signature -> viewModel.placeSignature(at, size)
            // The reach is on screen; the hit test works in image pixels.
            AnnotationTool.Eraser -> viewModel.eraseAt(at, size, eraserReach * size.width / rect.width)
        }
    }

    AnnotatedPageImage(
        image = image,
        annotations = uiState.annotations,
        contentDescription = null,
        onImageRect = { rect, size ->
            imageRect = rect
            imageSize = size
        },
        overlay = { rect ->
            if (current.isEmpty()) return@AnnotatedPageImage
            val unit = minOf(rect.width, rect.height)
            when (state.tool) {
                AnnotationTool.Pen -> drawStrokes(listOf(current.toList()), rgb(state.penColor), state.penWidth.value * unit)
                AnnotationTool.Highlighter -> {
                    val start = current.first()
                    val end = current.last()
                    drawRect(
                        color = rgb(state.highlightColor),
                        topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                        size = Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                        alpha = 0.4f,
                        blendMode = BlendMode.Multiply,
                    )
                }
                else -> Unit
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = ::tap) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        current.clear()
                        when (state.tool) {
                            AnnotationTool.Pen, AnnotationTool.Highlighter -> current.add(start)
                            AnnotationTool.Eraser -> tap(start)
                            else -> Unit
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val p = change.position
                        when (state.tool) {
                            AnnotationTool.Pen ->
                                if (current.isEmpty() || (p - current.last()).getDistance() >= minStep) current.add(p)
                            AnnotationTool.Highlighter -> {
                                if (current.size > 1) current.removeAt(current.lastIndex)
                                current.add(p)
                            }
                            AnnotationTool.Eraser -> tap(p)
                            else -> Unit
                        }
                    },
                    onDragEnd = {
                        val rect = imageRect
                        if (rect != null && current.isNotEmpty()) {
                            when (state.tool) {
                                AnnotationTool.Pen -> viewModel.addStroke(current.map { normalized(it, rect) })
                                AnnotationTool.Highlighter -> viewModel.addHighlight(
                                    normalized(current.first(), rect),
                                    normalized(current.last(), rect),
                                )
                                else -> Unit
                            }
                        }
                        current.clear()
                    },
                    onDragCancel = { current.clear() },
                )
            },
    )
}

@Composable
private fun ToolPanel(
    uiState: AnnotationEditorUiState,
    onTool: (AnnotationTool) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (PenWidth) -> Unit,
    onChooseSignature: (Signature) -> Unit,
    onDeleteSignature: (Signature) -> Unit,
    onNewSignature: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
    ) {
        when (uiState.tool) {
            AnnotationTool.Signature -> SignatureRow(
                signatures = uiState.signatures,
                chosen = uiState.chosenSignature,
                onChoose = onChooseSignature,
                onDelete = onDeleteSignature,
                onNew = onNewSignature,
            )
            AnnotationTool.Eraser -> Hint(R.string.eraser_hint)
            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                val highlighter = uiState.tool == AnnotationTool.Highlighter
                val colors = if (highlighter) HIGHLIGHT_COLORS else PEN_COLORS
                val selected = if (highlighter) uiState.highlightColor else uiState.penColor
                colors.forEachIndexed { index, color ->
                    ColorDot(
                        color = color,
                        selected = color == selected,
                        label = stringResource(colorName(highlighter, index)),
                        onClick = { onColor(color) },
                    )
                }
                if (uiState.tool == AnnotationTool.Pen) {
                    Spacer(Modifier.width(4.dp))
                    PenWidth.entries.forEach { width ->
                        DarkChip(
                            label = stringResource(width.labelRes()),
                            selected = width == uiState.penWidth,
                            onClick = { onWidth(width) },
                        )
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            AnnotationTool.entries.forEach { tool ->
                DarkChip(
                    label = stringResource(tool.labelRes()),
                    selected = tool == uiState.tool,
                    onClick = { onTool(tool) },
                )
            }
        }
    }
}

@Composable
private fun Hint(@StringRes text: Int) {
    Text(
        stringResource(text),
        color = Color.White.copy(alpha = 0.8f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SignatureRow(
    signatures: List<Signature>,
    chosen: Signature?,
    onChoose: (Signature) -> Unit,
    onDelete: (Signature) -> Unit,
    onNew: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hint(if (chosen == null) R.string.signature_choose_hint else R.string.signature_place_hint)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            signatures.forEach { signature ->
                Box {
                    SignaturePreview(
                        signature = signature,
                        selected = signature == chosen,
                        onClick = { onChoose(signature) },
                    )
                    IconButton(
                        onClick = { onDelete(signature) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.signature_forget),
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            DarkChip(label = stringResource(R.string.signature_new), selected = false, onClick = onNew)
        }
    }
}

@Composable
private fun SignaturePreview(signature: Signature, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.signature_saved)
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray),
        modifier = Modifier
            .size(width = 120.dp, height = 56.dp)
            .semantics { contentDescription = label },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Fit the signature's box into the card, keeping its shape.
            var w = size.width
            var h = w / signature.aspectRatio
            if (h > size.height) {
                h = size.height
                w = h * signature.aspectRatio
            }
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            drawStrokes(
                signature.strokes.map { stroke -> stroke.map { Offset(left + it.x * w, top + it.y * h) } },
                Color(0xFF1A4D8F),
                2.dp.toPx(),
            )
        }
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    // The whole dot is the touch target, not just the colour inside the ring.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            // A choice among colours: TalkBack says its name and whether it's selected.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.Gray, CircleShape)
            .padding(5.dp)
            .background(rgb(color), CircleShape),
    )
}

@Composable
private fun DarkChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun TextDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_text_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TEXT_CHARS) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.add_text))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A white pad to sign on with a finger; saved fitted into its own box. */
@Composable
private fun SignaturePadDialog(onDismiss: () -> Unit, onSave: (NormalizedSignature) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    val ink = Color(0xFF1A4D8F)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text(stringResource(R.string.signature_pad_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.signature_pad_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.White, MaterialTheme.shapes.small)
                        .border(1.dp, Color.LightGray, MaterialTheme.shapes.small)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    current.clear()
                                    current.add(it)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current.add(change.position)
                                },
                                onDragEnd = {
                                    strokes.add(current.toList())
                                    current.clear()
                                },
                                onDragCancel = { current.clear() },
                            )
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { strokes.add(listOf(it)) }) },
                ) {
                    drawStrokes(strokes + listOf(current.toList()), ink, 3.dp.toPx())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(R.string.clear_signature))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = { normalizeSignature(strokes.toList())?.let(onSave) },
                        enabled = strokes.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

/** The core accepts up to this many characters per text annotation. */
private const val MAX_TEXT_CHARS = 1000

private fun AnnotationTool.labelRes(): Int = when (this) {
    AnnotationTool.Pen -> R.string.tool_pen
    AnnotationTool.Highlighter -> R.string.tool_highlighter
    AnnotationTool.Text -> R.string.tool_text
    AnnotationTool.Signature -> R.string.tool_signature
    AnnotationTool.Eraser -> R.string.tool_eraser
}

private fun PenWidth.labelRes(): Int = when (this) {
    PenWidth.Fine -> R.string.pen_fine
    PenWidth.Medium -> R.string.pen_medium
    PenWidth.Thick -> R.string.pen_thick
}

private fun colorName(highlighter: Boolean, index: Int): Int =
    if (highlighter) {
        listOf(R.string.color_yellow, R.string.color_green, R.string.color_pink, R.string.color_light_blue)[index]
    } else {
        listOf(R.string.color_black, R.string.color_blue, R.string.color_red, R.string.color_green)[index]
    }

private fun AnnotationEditorMessage.textRes(): Int = when (this) {
    AnnotationEditorMessage.SaveFailed -> R.string.error_save_annotations
    AnnotationEditorMessage.SignatureFailed -> R.string.error_signature
    AnnotationEditorMessage.TooManySignatures -> R.string.error_too_many_signatures
    AnnotationEditorMessage.PlaceSignatureFirst -> R.string.signature_choose_hint
}
