package app.nolva.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Corners
import app.nolva.data.NormalizedPoint
import coil3.compose.AsyncImage
import java.io.File

/** How close (in dp) a touch must be to a corner handle to grab it. */
private val HANDLE_TOUCH_RADIUS = 40.dp
private val HANDLE_RADIUS = 11.dp

@Composable
fun CropRoute(
    viewModel: CropViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.saved) { if (uiState.saved) onDone() }

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
            CropTopBar(
                canApply = uiState.page != null && !uiState.isSaving,
                isSaving = uiState.isSaving,
                onClose = onDone,
                onApply = viewModel::apply,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val page = uiState.page
                when {
                    uiState.isLoading -> CircularProgressIndicator(color = Color.White)
                    page != null -> CropEditor(
                        image = page.originalFile,
                        corners = uiState.corners,
                        onCornerMoved = viewModel::moveCorner,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (uiState.shouldCheckCorners && !uiState.isLoading) {
                    Text(
                        text = stringResource(R.string.crop_check_corners),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(onClick = viewModel::selectFullImage, enabled = uiState.page != null) {
                    Text(stringResource(R.string.crop_full_image), color = Color.White)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp),
        )
    }
}

@Composable
private fun CropTopBar(canApply: Boolean, isSaving: Boolean, onClose: () -> Unit, onApply: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }
        Text(
            text = stringResource(R.string.crop_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (isSaving) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .size(20.dp),
            )
        } else {
            TextButton(onClick = onApply, enabled = canApply) {
                Text(stringResource(R.string.crop_apply), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * The original photo with a draggable quadrilateral on top.
 *
 * The photo is fitted (letterboxed) inside the available space; [imageRect]
 * is where it actually lands, and every touch is converted to normalized
 * image coordinates relative to it, which is what the core expects.
 */
@Composable
private fun CropEditor(
    image: File,
    corners: Corners,
    onCornerMoved: (Corner, NormalizedPoint) -> Unit,
) {
    var imageSize by remember(image) { mutableStateOf<Size?>(null) }
    val currentCorners by rememberUpdatedState(corners)
    val density = LocalDensity.current
    val touchRadius = with(density) { HANDLE_TOUCH_RADIUS.toPx() }
    val handleRadius = with(density) { HANDLE_RADIUS.toPx() }
    val primary = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val imageRect = imageSize?.let { fittedRect(it, container) }

        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Coil applies EXIF orientation, so these are display dimensions,
            // the same space the corners are expressed in.
            onSuccess = { state ->
                val img = state.result.image
                imageSize = Size(img.width.toFloat(), img.height.toFloat())
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (imageRect != null) {
            var dragging by remember { mutableStateOf<Corner?>(null) }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(imageRect) {
                        detectDragGestures(
                            onDragStart = { touch ->
                                dragging = Corner.entries
                                    .map { it to (toScreen(currentCorners.get(it), imageRect) - touch).getDistance() }
                                    .filter { (_, distance) -> distance <= touchRadius }
                                    .minByOrNull { (_, distance) -> distance }
                                    ?.first
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                            onDrag = { change, _ ->
                                val corner = dragging ?: return@detectDragGestures
                                change.consume()
                                onCornerMoved(corner, toNormalized(change.position, imageRect))
                            },
                        )
                    },
            ) {
                val points = Corner.entries.map { toScreen(corners.get(it), imageRect) }
                val quad = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                // Dim everything outside the selection.
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(imageRect)
                    addPath(quad)
                }
                drawPath(shade, Color.Black.copy(alpha = 0.55f))
                drawPath(quad, primary, style = Stroke(width = 2.dp.toPx()))
                points.forEachIndexed { index, point ->
                    val active = dragging == Corner.entries[index]
                    drawCircle(Color.White, radius = if (active) handleRadius * 1.4f else handleRadius, center = point)
                    drawCircle(primary, radius = handleRadius, center = point, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
    }
}

/** Where an image of [image] size lands when fitted (ContentScale.Fit) in [container]. */
internal fun fittedRect(image: Size, container: Size): Rect {
    val scale = minOf(container.width / image.width, container.height / image.height)
    val size = Size(image.width * scale, image.height * scale)
    val topLeft = Offset((container.width - size.width) / 2f, (container.height - size.height) / 2f)
    return Rect(topLeft, size)
}

internal fun toScreen(point: NormalizedPoint, rect: Rect) =
    Offset(rect.left + point.x * rect.width, rect.top + point.y * rect.height)

internal fun toNormalized(position: Offset, rect: Rect) = NormalizedPoint(
    ((position.x - rect.left) / rect.width).coerceIn(0f, 1f),
    ((position.y - rect.top) / rect.height).coerceIn(0f, 1f),
)

private fun CropMessage.textRes(): Int = when (this) {
    CropMessage.LoadFailed -> R.string.error_load_pages
    CropMessage.InvalidCorners -> R.string.crop_invalid_corners
    CropMessage.SaveFailed -> R.string.crop_save_failed
}
