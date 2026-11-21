package app.nolva.ui.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Page
import coil3.compose.AsyncImage

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
fun PageViewerRoute(
    viewModel: PageViewerViewModel,
    initialPage: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            )
        }

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
}

@Composable
private fun PagePager(pages: List<Page>, initialPage: Int) {
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

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        update(scale * zoomChange, offset + panChange)
    }

    AsyncImage(
        model = page.originalFile,
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
