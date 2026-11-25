package app.nolva.ui.viewer

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Page
import app.nolva.data.PageFilter
import app.nolva.ui.share.shareFile
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
fun PageViewerRoute(
    viewModel: PageViewerViewModel,
    initialPage: Int,
    onClose: () -> Unit,
    onAdjustCrop: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Reload when coming back from the crop editor.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = uiState.message?.let {
        stringResource(
            when (it) {
                PageViewerMessage.FilterFailed -> R.string.error_apply_filter
                PageViewerMessage.RotateFailed -> R.string.error_rotate_page
                PageViewerMessage.UndoFailed -> R.string.error_restore_page
                PageViewerMessage.ShareFailed -> R.string.error_share_page
                PageViewerMessage.TextPending -> R.string.text_pending
                PageViewerMessage.TextFailed -> R.string.error_load_text
            },
        )
    }
    LaunchedEffect(uiState.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }
    val context = LocalContext.current
    LaunchedEffect(uiState.pendingShare) {
        val image = uiState.pendingShare ?: return@LaunchedEffect
        shareFile(context, image, mimeType = "image/jpeg", subject = image.nameWithoutExtension)
        viewModel.shareHandled()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            uiState.isLoading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            uiState.loadFailed || uiState.pages.isEmpty() -> Text(
                text = stringResource(R.string.error_load_pages),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )

            else -> PagePager(
                pages = uiState.pages,
                initialPage = initialPage.coerceIn(0, uiState.pages.lastIndex),
                renderingPageId = uiState.renderingPageId,
                bulkProgress = uiState.bulkProgress,
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                onAdjustCrop = onAdjustCrop,
                onApplyFilter = viewModel::applyFilter,
                onApplyFilterToAll = viewModel::applyFilterToAll,
                onRotate = viewModel::rotate,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onShare = viewModel::sharePage,
                onShowText = viewModel::showText,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 128.dp)
                .align(Alignment.BottomCenter),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }
    }

    uiState.shownText?.let { shown ->
        PageTextSheet(shown = shown, onDismiss = viewModel::textDismissed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageTextSheet(shown: ShownText, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.page_text_title, shown.pageNumber)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (shown.text.isEmpty()) {
                Text(stringResource(R.string.no_text_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Selectable, to copy just a part; the button copies it all.
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(shown.text, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, shown.text)))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.copy_text))
                }
            }
        }
    }
}

@Composable
private fun PagePager(
    pages: List<Page>,
    initialPage: Int,
    renderingPageId: String?,
    bulkProgress: BulkProgress?,
    canUndo: Boolean,
    canRedo: Boolean,
    onAdjustCrop: (String) -> Unit,
    onApplyFilter: (pageId: String, filter: PageFilter) -> Unit,
    onApplyFilterToAll: (PageFilter) -> Unit,
    onRotate: (pageId: String, clockwise: Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShare: (pageId: String) -> Unit,
    onShowText: (pageId: String) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    // Swiping between pages is disabled while the current page is zoomed in,
    // so dragging pans the image instead of changing page.
    var currentPageZoomed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !currentPageZoomed,
            key = { pages[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            ZoomablePage(
                page = pages[index],
                // Only the visible page reports its zoom state.
                onZoomChanged = { zoomed -> if (index == pagerState.currentPage) currentPageZoomed = zoomed },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopEnd),
        ) {
            TextButton(onClick = { onShowText(pages[pagerState.currentPage].id) }) {
                Text(stringResource(R.string.show_text), color = Color.White)
            }
            IconButton(onClick = { onShare(pages[pagerState.currentPage].id) }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_page), tint = Color.White)
            }
            TextButton(onClick = { onAdjustCrop(pages[pagerState.currentPage].id) }) {
                Text(stringResource(R.string.adjust_crop), color = Color.White)
            }
        }

        val current = pages[pagerState.currentPage]
        val busy = renderingPageId != null || bulkProgress != null
        if (bulkProgress != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                    .padding(24.dp),
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    stringResource(R.string.applying_to_pages, bulkProgress.done, bulkProgress.total),
                    color = Color.White,
                )
            }
        } else if (current.id == renderingPageId) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(vertical = 4.dp),
        ) {
            EditBar(
                enabled = !busy,
                canUndo = canUndo,
                canRedo = canRedo,
                // Only worth offering when some page would change.
                showApplyToAll = pages.size > 1 && pages.any { it.filter != current.filter },
                onRotate = { clockwise -> onRotate(current.id, clockwise) },
                onApplyToAll = { onApplyFilterToAll(current.filter) },
                onUndo = onUndo,
                onRedo = onRedo,
            )
            FilterBar(
                selected = current.filter,
                enabled = !busy,
                onSelect = { filter -> onApplyFilter(current.id, filter) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        }

        Text(
            text = stringResource(R.string.page_counter, pagerState.currentPage + 1, pages.size),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 20.dp)
                .align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun EditBar(
    enabled: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    showApplyToAll: Boolean,
    onRotate: (clockwise: Boolean) -> Unit,
    onApplyToAll: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        EditIcon(R.drawable.ic_rotate_left, R.string.rotate_left, enabled) { onRotate(false) }
        EditIcon(R.drawable.ic_rotate_right, R.string.rotate_right, enabled) { onRotate(true) }
        Spacer(Modifier.weight(1f))
        if (showApplyToAll) {
            TextButton(onClick = onApplyToAll, enabled = enabled) {
                Text(
                    stringResource(R.string.apply_filter_to_all),
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        EditIcon(R.drawable.ic_undo, R.string.undo, enabled && canUndo, onUndo)
        EditIcon(R.drawable.ic_redo, R.string.redo, enabled && canRedo, onRedo)
    }
}

@Composable
private fun EditIcon(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(label),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun FilterBar(
    selected: PageFilter,
    enabled: Boolean,
    onSelect: (PageFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    enabled = enabled,
                    label = { Text(stringResource(filter.labelRes())) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        disabledLabelColor = Color.White.copy(alpha = 0.4f),
                        disabledSelectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    ),
                )
            }
        }
    }
}

private fun PageFilter.labelRes(): Int = when (this) {
    PageFilter.Original -> R.string.filter_original
    PageFilter.Grayscale -> R.string.filter_grayscale
    PageFilter.BlackAndWhite -> R.string.filter_black_and_white
    PageFilter.Enhanced -> R.string.filter_enhanced
}

@Composable
private fun ZoomablePage(page: Page, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val animatedScale by animateFloatAsState(scale, label = "zoom")

    fun update(newScale: Float, newOffset: Offset) {
        scale = newScale.coerceIn(1f, MAX_ZOOM)
        // Keep the zoomed image covering the screen: never pan past its edges.
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        offset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
        onZoomChanged(scale > 1f)
    }

    // Zoom around the fingers: keep the point under the pinch centroid fixed.
    // graphicsLayer scales around the centre, hence the centre-relative maths.
    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        val factor = newScale / scale
        val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
        update(newScale, pivot - (pivot - offset) * factor + panChange)
    }

    AsyncImage(
        model = page.displayFile,
        contentDescription = stringResource(R.string.page_number, page.position + 1),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(page.id) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) update(1f, Offset.Zero) else update(DOUBLE_TAP_ZOOM, Offset.Zero)
                    },
                )
            }
            // Pan only while zoomed; otherwise horizontal drags go to the pager.
            .transformable(transformState, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
